import sbt._

import scala.xml.NodeSeq

import Keys.TaskStreams

import ClasspathPlugin._
import xsbtUtil._

object OsxAppPlugin extends Plugin {
	//------------------------------------------------------------------------------
	//## exported vm choice
	
	sealed trait OsxAppVm
	
	case class AppleJava6(
		version:String	= "1.6",	// 1.6 or 1.6+ or 1.6*
		stub:File		= file("/System/Library/Frameworks/JavaVM.framework/Versions/Current/Resources/MacOS/JavaApplicationStub")
	) 
	extends OsxAppVm
	
	case class OracleJava7(
		command:String	= "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java"
	)
	extends OsxAppVm
	
	//------------------------------------------------------------------------------
	//## exported keys
	
	val osxapp					= taskKey[File]("complete build, returns the created directory")
	val osxappTargetDir			= settingKey[File]("where to put the application bundle")
	
	val osxappBundleId			= settingKey[String]("bundle id, defaults to organization . normalizedName")
	val osxappBundleName		= settingKey[String]("bundle naame without the \".app\" suffix")
	val osxappBundleVersion		= settingKey[String]("short version")
	val osxappBundleIcons		= settingKey[File](".icns file")
	val osxappVm				= settingKey[OsxAppVm]("AppleJava6 or OracleJava7")
	
	val osxappVmOptions			= settingKey[Seq[String]]("vm options like -Xmx128")
	val osxappSystemProperties	= settingKey[Map[String,String]]("-D in the command line")
	val osxappMainClass			= taskKey[Option[String]]("name of the main class")
	val osxappPrefixArguments	= settingKey[Seq[String]]("command line arguments")
	
	lazy val osxappSettings:Seq[Def.Setting[_]]	= 
			classpathSettings ++ 
			Vector(
				osxapp		:=
						buildTask(
							streams				= Keys.streams.value,
							assets				= classpathAssets.value,
							targetDir			= osxappTargetDir.value,
							bundleId			= osxappBundleId.value,
							bundleName			= osxappBundleName.value,
							bundleVersion		= osxappBundleVersion.value,
							bundleIcons			= osxappBundleIcons.value,
							vm					= osxappVm.value,
							mainClass			= osxappMainClass.value,
							vmOptions			= osxappVmOptions.value,
							systemProperties	= osxappSystemProperties.value,
							prefixArguments		= osxappPrefixArguments.value
						),
						
				osxappTargetDir			:= Keys.crossTarget.value / "osxapp",
				
				osxappBundleId			:= Keys.organization.value + "." + Keys.normalizedName.value,
				osxappBundleName		:= (Keys.name in Runtime).value,
				// TODO use version for CFBundleShortVersionString and add build number for CFBundleVersion
				osxappBundleVersion		:= Keys.version.value,
				// mandatory
				// osxappBundleIcons	:= null,
				osxappVm				:= OracleJava7(),
				
				osxappMainClass			:= Keys.mainClass.value,
				osxappVmOptions			:= Seq.empty,
				osxappSystemProperties	:= Map.empty,
				osxappPrefixArguments	:= Seq.empty,
				
				Keys.watchSources		:= Keys.watchSources.value :+ osxappBundleIcons.value
			)
	
	//------------------------------------------------------------------------------
	//## build task
	
	private def buildTask(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		targetDir:File,
		bundleId:String,
		bundleName:String,
		bundleVersion:String,
		bundleIcons:File,
		vm:OsxAppVm,
		mainClass:Option[String],
		vmOptions:Seq[String],
		systemProperties:Map[String,String],
		prefixArguments:Seq[String]
	):File = {
		val mainClassGot	= mainClass getOrElse failWithError(streams, s"${osxappMainClass.key.label} must be set")
		
		val executableName:String	= 
				vm match {
					case AppleJava6(jvmVersion, javaApplicationStub)	=> "JavaApplicationStub"
					case OracleJava7(javaCommand)						=> "run"
				}
				
		def plist:String	= 
				"""<?xml version="1.0" encoding="UTF-8"?>""" +
				"""<!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">"""	+
				<plist version="1.0">
					<dict>
						<key>CFBundleDevelopmentRegion</key>		<string>English</string>
						<key>CFBundleExecutable</key>				<string>{executableName}</string>
						<key>CFBundleGetInfoString</key>			<string>{bundleVersion}</string>
						<key>CFBundleIconFile</key>					<string>{bundleIcons.getName}</string>
						<key>CFBundleIdentifier</key>				<string>{bundleId}</string>
						<key>CFBundleInfoDictionaryVersion</key>	<string>6.0</string>
						<key>CFBundleName</key>						<string>{bundleName}</string>
						<key>CFBundlePackageType</key>				<string>APPL</string>
						<key>CFBundleShortVersionString</key>		<string>{bundleVersion}</string>
						<key>CFBundleSignature</key>				<string>????</string>
						<key>CFBundleVersion</key>					<string>{bundleVersion}</string>
						{plistInner}
					</dict>
				</plist>
		
		def plistInner:NodeSeq	=
				vm match {
					case AppleJava6(jvmVersion, _)	=> appStubConfig(jvmVersion)
					case OracleJava7(javaCommand)	=> <xml:group></xml:group>
				}
				
		def appStubConfig(_jvmVersion:String):NodeSeq	= {
			def stringXml(s:String)				= <string>{ s }</string>
			def arrayXml(ss:Seq[String])		= <array>{ ss map stringXml }</array>
			def keyXml(s:String)				= <key>{ s }</key>
			def itemXml(sp:(String,String))		= <xml:group>{ keyXml(sp._1) }{ stringXml(sp._2) }</xml:group>
			def dictXml(ss:Map[String,String])	= <dict>{ ss map itemXml }</dict>
			
			val jvmVersionXml		= stringXml(_jvmVersion)
			val mainClassXml		= stringXml(mainClassGot)
			val classPathXml		= stringXml(assets	map { _.name } mkString ":")
			val vmOptionsXml		= arrayXml(vmOptions)
			val systemPropertiesXml	= dictXml(systemProperties)
			val prefixArgumentsXml	= arrayXml(prefixArguments)
			val out:NodeSeq	=
					<xml:group>
						<!-- 
						$JAVAROOT		Contents/Resources/Java
						$APP_PACKAGE	the root directory of the bundle
						$USER_HOME		the current user's home directory
						-->
						<key>Java</key>
						<dict>
							<key>JVMVersion</key>		{jvmVersionXml}
							<key>MainClass</key>		{mainClassXml}
							<key>WorkingDirectory</key>	<string>$JAVAROOT</string>
							<key>VMOptions</key>		{vmOptionsXml}
							<key>Arguments</key>		{prefixArgumentsXml}
							<key>ClassPath</key>		{classPathXml}
							<key>Properties</key>		{systemPropertiesXml}
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
			
		def executableData:Either[File,String]	=
				vm match {
					case AppleJava6(_, javaApplicationStub)	=> Left(javaApplicationStub)
					case OracleJava7(javaCommand)			=> Right(shellScript(javaCommand))
				}
				
		def shellScript(javaCommand:String):String	= {
			val parts:Seq[Seq[String]]	= 
					Vector( 
						Vector(javaCommand)							map unixHardQuote,
						vmOptions									map unixHardQuote,
						scriptSystemPropertyMap(systemProperties)	map unixHardQuote,
						Vector("-cp")								map unixHardQuote,
						Vector(""""$base"/'*'"""),
						Vector(mainClassGot)						map unixHardQuote,
						prefixArguments								map unixHardQuote
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
		
		val appOut	= targetDir / (bundleName + ".app") 
		streams.log info s"building osx app in ${appOut}"
		
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
		
		IO copyFile	(bundleIcons,	contents / "Resources" / bundleIcons.getName)
		val assetsToCopy	= assets map { _.flatPathMapping } map (PathMapping anchorTo contents / "Resources" / "Java")
		IO copy assetsToCopy
		
		appOut
	}
}
