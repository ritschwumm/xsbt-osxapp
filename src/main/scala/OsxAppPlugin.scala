import sbt._

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._

import java.nio.charset.Charset

object OsxAppPlugin extends Plugin {
   private def orr[A,T>:A](key:TaskKey[A], rhs:Initialize[Task[T]]):Initialize[Task[T]]	=
   	   	(key.? zipWith rhs) { (x,y) => (x :^: y :^: KNil) map (Scoped hf2 { _ getOrElse _ }) }
   	
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
	
	// name of the main class
	val osxappMainClass		= TaskKey[Option[String]]("osxapp-main-class")
	
	// in megabytes
	val osxappHeapSize		= SettingKey[Int]("osxapp-heap-size")
	
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
		osxappMainClass			<<= orr(Keys.mainClass, Keys.selectMainClass in Runtime),
		osxappHeapSize			:= 128,
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
			mainClass:Option[String],
			heapSize:Int,
			applicationStub:File)
			
	private def dataTask:Initialize[Task[Data]] = 
		(	osxappBaseDirectory,				
			osxappBundleId,
			osxappBundleName,
			osxappBundleVersion,
			osxappBundleIcons,
			osxappMainClass,
			osxappHeapSize,
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
		require(data.bundleIcons		!= null, "osxapp-icons must be set")
		val mainClass	= data.mainClass getOrElse (sys error "osxapp-main-class must be set")
		
		val classPath	= assets map { _.name } mkString ":"
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
						<!-- $JAVAROOT $APP_PACKAGE -->
						<key>Java</key><dict>
							<key>JVMVersion</key>		<string>1.6+</string>
							<key>MainClass</key>		<string>{data.mainClass}</string>
							<key>WorkingDirectory</key>	<string>$JAVAROOT</string>
							<key>VMOptions</key>		<string>-Xmx{data.heapSize}m</string>
							<key>Arguments</key>		<string></string>
							<key>ClassPath</key>		<string>{classPath}</string>
							<key>Properties</key><dict>
								<!-- 
								<key>apple.awt.brushMetalLook</key>							<string>true</string>
								<key>com.apple.macos.useScreenMenuBar</key>					<string>true</string>
								<key>com.apple.mrj.application.apple.menu.about.name</key>	<string>{bundleName}</string>
								<key>com.apple.mrj.application.growbox.intrudes</key>		<string>true</string>
								<key>com.apple.mrj.application.live-resize</key>			<string>true</string>
								-->
							</dict>
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
}
