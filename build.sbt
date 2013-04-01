sbtPlugin		:= true

name			:= "xsbt-osxapp"

organization	:= "de.djini"

version			:= "0.5.0"

scalacOptions	++= Seq("-deprecation", "-unchecked")

libraryDependencies <+= (sbtBinaryVersion in update, scalaVersion) { (sbtV, scalaV) =>
	Defaults.sbtPluginExtra("de.djini" % "xsbt-classpath" % "0.3.0", sbtV, scalaV) % "compile"
}
