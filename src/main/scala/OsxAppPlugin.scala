import sbt._

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._

import java.nio.charset.Charset

object OsxAppPlugin extends Plugin {
	//------------------------------------------------------------------------------
	//## exported
	
	// complete build, returns the created directory
	val osxappBuild			= TaskKey[File]("osxapp")
	
	// build data helper
	private val osxappData	= TaskKey[Data]("osxapp-data")
	
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
	
	// 1.6 or 1.6+ or 1.6*
	val osxappJvmVersion	= TaskKey[String]("osxapp-jvm-version")
	
	// name of the main class
	val osxappMainClass		= TaskKey[Option[String]]("osxapp-main-class")
	
	// vm options like -Xmx128m
	val osxappVmOptions		= SettingKey[Seq[String]]("osxapp-vm-options")
	
	// -D in the command line
	val osxappProperties	= SettingKey[Map[String,String]]("osxapp-properties")
	
	// command line arguments
	val osxappArguments		= SettingKey[Seq[String]]("osxapp-arguments")
	
	// JavaApplicationStub
	val osxappApplicationStub	= SettingKey[File]("osxapp-application-stub")
	
	lazy val osxappSettings:Seq[Project.Setting[_]]	= classpathSettings ++ Seq(
		osxappBuild				<<= buildTask,
		osxappData				<<= dataTask,
		
		osxappBaseDirectory		<<= Keys.crossTarget { _ / "osxapp" },
		osxappBundleId			<<= (Keys.organization, Keys.normalizedName) { _ + "." + _ },
		osxappBundleName		<<= Keys.name in Runtime,
		// TODO use version for CFBundleShortVersionString and add build number for CFBundleVersion
		osxappBundleVersion		<<= Keys.version,
		osxappBundleIcons		:= null,	// TODO ugly
		osxappJvmVersion		:= "1.6+",
		osxappMainClass			<<= orr(Keys.mainClass, Keys.selectMainClass in Runtime),
		osxappVmOptions			:= Seq.empty,
		osxappProperties		:= Map.empty,
		osxappArguments			:= Seq.empty,
		osxappApplicationStub	:= file("/System/Library/Frameworks/JavaVM.framework/Versions/Current/Resources/MacOS/JavaApplicationStub")
	)
	
	//------------------------------------------------------------------------------
	//## data task
	
	private case class Data(
			base:File,
			bundleId:String,
			bundleName:String,
			bundleVersion:String,
			bundleIcons:File,
			jvmVersion:String,
			mainClass:Option[String],
			vmOptions:Seq[String],
			properties:Map[String,String],
			arguments:Seq[String],
			applicationStub:File)
			
	private def dataTask:Initialize[Task[Data]] = 
		(	osxappBaseDirectory,				
			osxappBundleId,
			osxappBundleName,
			osxappBundleVersion,
			osxappBundleIcons,
			osxappJvmVersion,
			osxappMainClass,
			osxappVmOptions,
			osxappProperties,
			osxappArguments,
			osxappApplicationStub
		) map
		Data.apply
	
	//------------------------------------------------------------------------------
	//## build task
	
	private def buildTask:Initialize[Task[File]] = (
		Keys.streams,
		classpathAssets,	
		osxappData
	) map buildTaskImpl
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[Asset],
		data:Data
	):File = {
		require(data.bundleIcons	!= null, "osxapp-icons must be set")
		
		val jvmVersion	= <string>{	data.jvmVersion																}</string>
		val mainClass	= <string>{	data.mainClass getOrElse (sys error "osxapp-main-class must be set")		}</string>
		val classPath	= <string>{	assets	map { _.name } mkString ":"											}</string>
		val vmOptions	= <array>{	data.vmOptions	map  { it => <string>{it}</string> }						}</array>
		val properties	= <dict>{	data.properties	map  { it => <key>{it._1}</key><string>{it._2}</string>	}	}</dict>
		val arguments	= <array>{	data.arguments	map  { it => <string>{it}</string> }						}</array>
		val appStubName	= "JavaApplicationStub"
		
		val plist	=
				"""<?xml version="1.0" encoding="UTF-8"?>""" +
				"""<!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">"""	+
				<plist version="1.0">
					<dict>
						<key>CFBundleDevelopmentRegion</key>		<string>English</string>
						<key>CFBundleExecutable</key>				<string>{appStubName}</string>
						<key>CFBundleGetInfoString</key>			<string>{data.bundleVersion}</string>
						<key>CFBundleIconFile</key>					<string>{data.bundleIcons.getName}</string>
						<key>CFBundleIdentifier</key>				<string>{data.bundleId}</string>
						<key>CFBundleInfoDictionaryVersion</key>	<string>6.0</string>
						<key>CFBundleName</key>						<string>{data.bundleName}</string>
						<key>CFBundlePackageType</key>				<string>APPL</string>
						<key>CFBundleShortVersionString</key>		<string>{data.bundleVersion}</string>
						<key>CFBundleSignature</key>				<string>????</string>
						<key>CFBundleVersion</key>					<string>{data.bundleVersion}</string>
						<!-- 
						$JAVAROOT		Contents/Resources/Java
						$APP_PACKAGE	the root directory of the bundle
						$USER_HOME		the current user's home directory
						-->
						<key>Java</key><dict>
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
					</dict>
				</plist>
				
		//------------------------------------------------------------------------------
				
		val	utf_8	= Charset forName "UTF-8"
		val appOut	= data.base / (data.bundleName + ".app") 
		streams.log info ("building app in " + appOut)
		
		// TODO inelegant
		IO delete			appOut
		IO createDirectory	appOut
		
		IO write (appOut / "Contents" / "PkgInfo", 	"APPL????",	utf_8)
		IO write (appOut / "Contents" / "Info.plist",	plist,		utf_8)
		IO copyFile	(data.applicationStub,	appOut / "Contents" / "MacOS" / appStubName)
		appOut / "Contents" / "MacOS" / appStubName setExecutable (true, false)
		IO copyFile	(data.bundleIcons,	appOut / "Contents" / "Resources" / data.bundleIcons.getName)
		
		val assetsToCopy	=
				assets map { asset =>
					(asset.jar, appOut / "Contents" / "Resources" / "Java" / asset.name)
				}
		IO copy assetsToCopy
		
		appOut
	}
	
	//------------------------------------------------------------------------------
	//## utils
	
	private def orr[A,T>:A](key:TaskKey[A], rhs:Initialize[Task[T]]):Initialize[Task[T]]	=
			(key.? zipWith rhs) { (x,y) => (x :^: y :^: KNil) map (Scoped hf2 { _ getOrElse _ }) }
}
