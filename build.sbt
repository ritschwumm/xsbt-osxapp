sbtPlugin		:= true

name			:= "xsbt-osxapp"

organization	:= "de.djini"

version			:= "0.7.0"

addSbtPlugin("de.djini" % "xsbt-classpath" % "0.5.0")
	
scalacOptions	++= Seq(
	"-deprecation",
	"-unchecked",
	// "-language:implicitConversions",
	// "-language:existentials",
	// "-language:higherKinds",
	// "-language:reflectiveCalls",
	// "-language:dynamics",
	// "-language:postfixOps",
	// "-language:experimental.macros"
	"-feature"
)
