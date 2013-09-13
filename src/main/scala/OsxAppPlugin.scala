import sbt._

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._

import scala.xml.NodeSeq

import java.nio.charset.Charset

object OsxAppPlugin extends Plugin {
	// build data helper
	private val osxappData	= TaskKey[Data]("osxapp-data")
	
	//------------------------------------------------------------------------------
	//## exported vm choice
	
	sealed trait OsxAppVm
	
	case class AppleJava6(
		version:String	= "1.6",	// 1.6 or 1.6+ or 1.6*
		stub:File		= file("/System/Library/Frameworks/JavaVM.framework/Versions/Current/Resources/MacOS/JavaApplicationStub")
	) 
	extends OsxAppVm
	
	case class OracleJava7(
		command:String = "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java"
	)
	extends OsxAppVm
	
	//------------------------------------------------------------------------------
	//## exported keys
	
	// complete build, returns the created directory
	val osxappBuild			= TaskKey[File]("osxapp")
	
	// where to put the application bundle
	val osxappBaseDirectory	= SettingKey[File]("osxapp-base-directory")
	
	// organization . normalizedName by default
	val osxappBundleId		= SettingKey[String]("osxapp-bundle-id")
	
	// without the ".app" suffix
	val osxappBundleName	= SettingKey[String]("osxapp-bundle-name")
	
	// short version
	val osxappBundleVersion	= SettingKey[String]("osxapp-bundle-version")
	
	// .icns file
	val osxappBundleIcons	= SettingKey[File]("osxapp-bundle-icons")
	
	// AppleJava6 or OracleJava7
	val osxappVm			= SettingKey[OsxAppVm]("osxapp-vm")
	
	// name of the main class
	val osxappMainClass		= TaskKey[Option[String]]("osxapp-main-class")
	
	// vm options like -Xmx128m
	val osxappVmOptions		= SettingKey[Seq[String]]("osxapp-vm-options")
	
	// -D in the command line
	val osxappProperties	= SettingKey[Map[String,String]]("osxapp-properties")
	
	// command line arguments
	val osxappArguments		= SettingKey[Seq[String]]("osxapp-arguments")
	
	lazy val osxappSettings:Seq[Def.Setting[_]]	= 
			classpathSettings ++ 
			Seq(
				osxappData				<<= dataTask,
				osxappBuild				<<= buildTask,
				osxappBaseDirectory		<<= Keys.crossTarget { _ / "osxapp" },
				
				osxappBundleId			<<= (Keys.organization, Keys.normalizedName) { _ + "." + _ },
				osxappBundleName		<<= Keys.name in Runtime,
				// TODO use version for CFBundleShortVersionString and add build number for CFBundleVersion
				osxappBundleVersion		<<= Keys.version,
				// TODO ugly
				osxappBundleIcons		:= null,
				osxappVm				:= OracleJava7(),
				
				osxappMainClass			<<= Keys.mainClass,
				osxappVmOptions			:= Seq.empty,
				osxappProperties		:= Map.empty,
				osxappArguments			:= Seq.empty
			)
	
	//------------------------------------------------------------------------------
	//## data task
	
	private case class Data(
			base:File,
			bundleId:String,
			bundleName:String,
			bundleVersion:String,
			bundleIcons:File,
			vm:OsxAppVm,
			mainClass:Option[String],
			vmOptions:Seq[String],
			properties:Map[String,String],
			arguments:Seq[String])
			
	private def dataTask:Def.Initialize[Task[Data]] = 
		(	osxappBaseDirectory,				
			osxappBundleId,
			osxappBundleName,
			osxappBundleVersion,
			osxappBundleIcons,
			osxappVm,
			osxappMainClass,
			osxappVmOptions,
			osxappProperties,
			osxappArguments
		) map
		Data.apply
	
	//------------------------------------------------------------------------------
	//## build task
	
	private def buildTask:Def.Initialize[Task[File]] = (
		Keys.streams,
		classpathAssets,	
		osxappData
	) map buildTaskImpl
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		data:Data
	):File = {
		require(data.bundleIcons	!= null, "osxapp-icons must be set")
		
		val executableName	= 
				data.vm match {
					case AppleJava6(jvmVersion, javaApplicationStub)	=> "JavaApplicationStub"
					case OracleJava7(javaCommand)						=> "run"
				}
				
		def plist	= 
				"""<?xml version="1.0" encoding="UTF-8"?>""" +
				"""<!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">"""	+
				<plist version="1.0">
					<dict>
						<key>CFBundleDevelopmentRegion</key>		<string>English</string>
						<key>CFBundleExecutable</key>				<string>{executableName}</string>
						<key>CFBundleGetInfoString</key>			<string>{data.bundleVersion}</string>
						<key>CFBundleIconFile</key>					<string>{data.bundleIcons.getName}</string>
						<key>CFBundleIdentifier</key>				<string>{data.bundleId}</string>
						<key>CFBundleInfoDictionaryVersion</key>	<string>6.0</string>
						<key>CFBundleName</key>						<string>{data.bundleName}</string>
						<key>CFBundlePackageType</key>				<string>APPL</string>
						<key>CFBundleShortVersionString</key>		<string>{data.bundleVersion}</string>
						<key>CFBundleSignature</key>				<string>????</string>
						<key>CFBundleVersion</key>					<string>{data.bundleVersion}</string>
						{plistInner}
					</dict>
				</plist>
		
		def plistInner:NodeSeq	=
				data.vm match {
					case AppleJava6(jvmVersion, _)	=> appStubConfig(jvmVersion)
					case OracleJava7(javaCommand)	=> <xml:group></xml:group>
				}
				
		def appStubConfig(_jvmVersion:String):NodeSeq	= {
			val jvmVersion	= <string>{	_jvmVersion																	}</string>
			val mainClass	= <string>{	data.mainClass getOrElse (sys error "osxapp-main-class must be set")		}</string>
			val classPath	= <string>{	assets	map { _.name } mkString ":"											}</string>
			val vmOptions	= <array>{	data.vmOptions	map  { it => <string>{it}</string> }						}</array>
			val properties	= <dict>{	data.properties	map  { it => <key>{it._1}</key><string>{it._2}</string>	}	}</dict>
			val arguments	= <array>{	data.arguments	map  { it => <string>{it}</string> }						}</array>
			val out:NodeSeq	=
					<xml:group>
						<!-- 
						$JAVAROOT		Contents/Resources/Java
						$APP_PACKAGE	the root directory of the bundle
						$USER_HOME		the current user's home directory
						-->
						<key>Java</key>
						<dict>
							<key>JVMVersion</key>		{jvmVersion}
							<key>MainClass</key>		{mainClass}
							<key>WorkingDirectory</key>	<string>$JAVAROOT</string>
							<key>VMOptions</key>		{vmOptions}
							<key>Arguments</key>		{arguments}
							<key>ClassPath</key>		{classPath}
							<key>Properties</key>		{properties}
							<!-- 
							<key>apple.awt.brushMetalLook</key>							<string>true</string>
							<key>com.apple.macos.useScreenMenuBar</key>					<string>true</string>
							<key>com.apple.mrj.application.apple.menu.about.name</key>	<string>{bundleName}</string>
							<key>com.apple.mrj.application.growbox.intrudes</key>		<string>true</string>
							<key>com.apple.mrj.application.live-resize</key>			<string>true</string>
							-->
						</dict>
					</xml:group>
			out
		}
			
		def executableData	=
				data.vm match {
					case AppleJava6(_, javaApplicationStub)	=> Left(javaApplicationStub)
					case OracleJava7(javaCommand)			=> Right(shellScript(javaCommand))
				}
				
		def shellScript(javaCommand:String):String	= {
			def quote(s:String):String				= "'" + (s replace ("'", "'\"'\"'")) + "'" 
			def property(p:(String,String)):String	= "-D" + p._1 + "=" + p._2
			def main(s:Option[String]):String		= s getOrElse (sys error "osxapp-main-class not set")
			val parts:Seq[Seq[String]]	= Seq( 
				Seq(javaCommand)					map quote,
				data.vmOptions						map quote,
				data.properties.toSeq map property	map quote,
				Seq("-cp")							map quote,
				Seq(""""$base"/'*'"""),
				Seq(main(data.mainClass))			map quote,
				data.arguments						map quote
			)
			val command	= parts.flatten mkString " "
			// export LC_CTYPE="en_US.UTF-8"
			val script	= """#!/bin/bash
			base="$(dirname "$0")"/../Resources/Java
			""" + command + """
			"""
			script
		}
		
		//------------------------------------------------------------------------------
		
		val appOut	= data.base / (data.bundleName + ".app") 
		streams.log info ("building osx app in " + appOut)
		
		// TODO inelegant
		IO delete			appOut
		IO createDirectory	appOut
		
		val contents	= appOut / "Contents"
		IO write (contents / "PkgInfo", 	"APPL????",	IO.utf8)
		IO write (contents / "Info.plist",	plist,		IO.utf8)
		
		val executableFile	= contents / "MacOS" / executableName
		executableData match {
			case Left(file)		=> IO copyFile	(file, executableFile)
			case Right(string)	=> IO write		(executableFile, string, IO.utf8)
		}
		executableFile setExecutable (true, false)
		
		IO copyFile	(data.bundleIcons,	contents / "Resources" / data.bundleIcons.getName)
		val assetsToCopy	= assets map { asset =>
			(asset.jar, contents / "Resources" / "Java" / asset.name)
		}
		IO copy assetsToCopy
		
		appOut
	}
}
