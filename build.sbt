Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / versionScheme := Some("early-semver")

sbtPlugin		:= true

name			:= "xsbt-osxapp"
organization	:= "de.djini"
version			:= "2.9.0"

scalacOptions	++= Seq(
	"-feature",
	"-deprecation",
	"-unchecked",
	"-Xfatal-warnings",
)

addSbtPlugin("de.djini" % "xsbt-util"		% "1.7.0")
addSbtPlugin("de.djini" % "xsbt-classpath"	% "2.8.0")
