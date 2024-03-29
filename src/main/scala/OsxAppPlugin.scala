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

	/** this is the standard case for newer installations like AdoptOpenJDK and uses /usr/libexec/java_home -v */
	final case class JavaHomeVersion(version:String) extends OsxAppVm

	/** use the default java installation, uses /usr/libexec/java_home _without_ "-v" */
	final case object JavaHomeDefault extends OsxAppVm

	/** for oracle java 8+, goes to "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java" */
	final case object JavaPlugin extends OsxAppVm

	/** for oracle java 8+ with manually installed java binaries */
	final case class JavaExecutable(path:String) extends OsxAppVm

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
	val osxappVm				= settingKey[OsxAppVm]("JavaHomeVersion, JavaHomeDefault, JavaPlugin or JavaExecutable")

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
			osxappBundleName		:= (Runtime / Keys.name).value,
			// TODO use version for CFBundleShortVersionString and add build number for CFBundleVersion
			osxappBundleVersion		:= Keys.version.value,
			// mandatory
			// osxappBundleIcons	:= null,
			osxappVm				:= JavaHomeVersion("1.8+"),

			osxappMainClass			:= (Runtime / Keys.mainClass).value,
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

		val executableName:String	= "run"

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

		def plistInner:NodeSeq	= <xml:group></xml:group>

		def shellScript(variant:OsxAppVm):String	= {
			def usePath(path:String):String	=
				"java_executable=" + (xu.script unixHardQuote path)

			def useVersion(version:Option[String]):String	=
				("""
				|java_home=$(/usr/libexec/java_home """ ++ (version map { v => "-v " + xu.script.unixHardQuote(v) } getOrElse "") ++ """ 2>/dev/null) || {
				|	echo >&2 "no suitable java installation found"
				|	exit 1
				|}
				|java_executable="$java_home/bin/java"
				""")
				.stripMargin

			val executable:String	=
				variant match {
					case JavaPlugin					=> usePath("/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java")
					case JavaExecutable(path)		=> usePath(path)
					case JavaHomeDefault			=> useVersion(None)
					case JavaHomeVersion(version)	=> useVersion(Some(version))
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
				Vector(
					"""#!/bin/bash""",
					"""base="$(dirname "$0")"/../Resources/Java""",
					executable,
					command
				)
				.mkString("", "\n", "\n")
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
		val executableData	= shellScript(vm)
		IO write (executableFile, executableData, IO.utf8)
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
