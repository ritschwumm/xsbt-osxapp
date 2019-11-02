package xsbtOsxApp

import scala.xml.NodeSeq

import sbt._
import Keys.TaskStreams

import xsbtUtil.types._
import xsbtUtil.{ util => xu }

import xsbtClasspath.{ Asset => ClasspathAsset, ClasspathPlugin }
import xsbtClasspath.Import.classpathAssets

object Import {
	sealed trait OsxAppVm

	/** for apple java 6 */
	final case class JavaApplicationStub(
		version:String	= "1.6",	// 1.6 or 1.6+ or 1.6*
		// this exists on old macs where java 6 was installed
		stub:File		= file("/System/Library/Frameworks/JavaVM.framework/Versions/Current/Resources/MacOS/JavaApplicationStub")
	)
	extends OsxAppVm

	/** for oracle java 8+ */
	final case class JavaExecutable(
		// this is installed by the oracle jdk
		command:String	= "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java"
	)
	extends OsxAppVm

	/** for newer installations like AdoptOpenJDK */
	final case class JavaFromHome(
		// uses the first version found using java_home
		versions:Seq[String]	= Vector("1.8")
	)
	extends OsxAppVm

	//------------------------------------------------------------------------------

	val osxapp					= taskKey[File]("complete build, returns the created directory")
	val osxappPackageName		= settingKey[String]("name of the package built")
	val osxappAppDir			= settingKey[File]("where to put the application bundle")

	val osxappZip				= taskKey[File]("complete build, returns the created application zip file")
	val osxappAppZip			= settingKey[File]("where to put the application zip file")

	val osxappBundleId			= settingKey[String]("bundle id, defaults to organization . normalizedName")
	val osxappBundleName		= settingKey[String]("bundle name without the \".app\" suffix")
	val osxappBundleVersion		= settingKey[String]("short version")
	val osxappBundleIcons		= settingKey[File](".icns file")
	val osxappVm				= settingKey[OsxAppVm]("JavaApplicationStub, JavaExecutable or JavaFromHome")

	val osxappMainClass			= taskKey[Option[String]]("name of the main class")
	val osxappVmOptions			= settingKey[Seq[String]]("vm options like -Xmx128")
	val osxappSystemProperties	= settingKey[Map[String,String]]("-D in the command line")
	val osxappPrefixArguments	= settingKey[Seq[String]]("command line arguments")

	val osxappBuildDir			= settingKey[File]("base directory of built files")
}

object OsxAppPlugin extends AutoPlugin {
	//------------------------------------------------------------------------------
	//## exports

	lazy val autoImport	= Import
	import autoImport._

	override val requires:Plugins		= ClasspathPlugin && plugins.JvmPlugin

	override val trigger:PluginTrigger	= noTrigger

	override lazy val projectSettings:Seq[Def.Setting[_]]	=
			Vector(
				osxapp		:=
						buildTask(
							streams				= Keys.streams.value,
							assets				= classpathAssets.value,
							appDir				= osxappAppDir.value,
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
				osxappAppDir			:= osxappBuildDir.value / osxappPackageName.value,

				osxappZip	:=
						zipTask(
							streams	= Keys.streams.value,
							appDir	= osxapp.value,
							prefix	= osxappPackageName.value,
							appZip	= osxappAppZip.value
						),
				osxappAppZip			:= osxappBuildDir.value / (osxappPackageName.value + ".zip"),

				osxappPackageName		:= Keys.name.value + "-" + Keys.version.value + ".app",

				osxappBundleId			:= Keys.organization.value + "." + Keys.normalizedName.value,
				osxappBundleName		:= (Keys.name in Runtime).value,
				// TODO use version for CFBundleShortVersionString and add build number for CFBundleVersion
				osxappBundleVersion		:= Keys.version.value,
				// mandatory
				// osxappBundleIcons	:= null,
				osxappVm				:= JavaExecutable(),

				osxappMainClass			:= (Keys.mainClass in Runtime).value,
				osxappVmOptions			:= Seq.empty,
				osxappSystemProperties	:= Map.empty,
				osxappPrefixArguments	:= Seq.empty,

				osxappBuildDir			:= Keys.crossTarget.value / "osxapp",

				Keys.watchSources		:= Keys.watchSources.value :+ Watched.WatchSource(osxappBundleIcons.value)
			)

	//------------------------------------------------------------------------------
	//## build task

	private def buildTask(
		streams:TaskStreams,
		assets:Seq[ClasspathAsset],
		appDir:File,
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
		val mainClassGot	=
				mainClass getOrElse {
					xu.fail logging (streams, s"${osxappMainClass.key.label} must be set")
				}

		val executableName:String	=
				vm match {
					case JavaApplicationStub(_, _)	=> "JavaApplicationStub"
					case JavaExecutable(_)			=> "run"
					case JavaFromHome(_)			=> "run"
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
					case JavaApplicationStub(jvmVersion, _)	=> appStubConfig(jvmVersion)
					case JavaExecutable(_)					=> <xml:group></xml:group>
					case JavaFromHome(_)					=> <xml:group></xml:group>
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
					case JavaApplicationStub(_, javaApplicationStub)	=> Left(javaApplicationStub)
					case JavaExecutable(javaCommand)					=> Right(shellScript(Right(javaCommand)))
					case JavaFromHome(versions)							=> Right(shellScript(Left(versions)))
				}

		def shellScript(versionsOrCommand:Either[Seq[String],String]):String	= {
			def usePath(path:String):String	=
					"java_executable=" + (xu.script unixHardQuote path)

			def useVersions(versions:Seq[String]):String	=
					"""
					java_home=
					for version in """ ++ (versions map xu.script.unixHardQuote mkString " ") ++ """; do
						if java_home=$(/usr/libexec/java_home -v "$version" 2>/dev/null); then break; fi
					done
					[ -n "$java_home" ] || {
						echo >&2 "no suitable java installation found"
						exit 1
					}
					java_executable="$java_home/bin/java"
					"""

			val executable:String	=
					versionsOrCommand match {
						case Left(versions)	=> useVersions(versions)
						case Right(path)	=> usePath(path)
					}

			val command:String	=
					Vector(
						Vector("$java_executable")					map xu.script.unixSoftQuote,
						vmOptions									map xu.script.unixHardQuote,
						xu.script systemProperties systemProperties	map xu.script.unixHardQuote,
						Vector("-cp")								map xu.script.unixHardQuote,
						Vector(""""$base"/'*'"""),
						Vector(mainClassGot)						map xu.script.unixHardQuote,
						prefixArguments								map xu.script.unixHardQuote
					)
					.flatten.mkString(" ")

			// export LC_CTYPE="en_US.UTF-8"
			val script	=
					"""#!/bin/bash
					base="$(dirname "$0")"/../Resources/Java
					""" + executable	+ """
					""" + command		+ """
					"""
			script
		}

		//------------------------------------------------------------------------------

		streams.log info s"building osx app in ${appDir}"

		// TODO inelegant
		IO delete			appDir
		IO createDirectory	appDir

		val contents	= appDir / "Contents"
		IO write (contents / "PkgInfo", 	"APPL????",	IO.utf8)
		IO write (contents / "Info.plist",	plist,		IO.utf8)

		val executableFile	= contents / "MacOS" / executableName
		executableData match {
			case Left(file)		=> IO copyFile	(file, executableFile)
			case Right(string)	=> IO write		(executableFile, string, IO.utf8)
		}
		executableFile setExecutable (true, false)

		IO copyFile	(bundleIcons,	contents / "Resources" / bundleIcons.getName)
		val assetsToCopy	= assets map { _.flatPathMapping } map (xu.pathMapping anchorTo contents / "Resources" / "Java")
		IO copy assetsToCopy

		appDir
	}

	/** build app zip */
	private def zipTask(
		streams:TaskStreams,
		appDir:File,
		prefix:String,
		appZip:File
	):File = {
		streams.log info s"creating zip file ${appZip}"
		xu.zip create (
			sources		= xu.find filesMapped appDir map (xu.pathMapping prefixPath prefix),
			outputZip	= appZip
		)
		appZip
	}
}
