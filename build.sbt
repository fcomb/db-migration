name := "db-migration"

organization := "io.fcomb"

version := "0.2.0"

crossScalaVersions := Seq("2.11.7", "2.10.5")

libraryDependencies ++= Seq(
  "ch.qos.logback" %  "logback-classic" % "1.1.3",
  "org.specs2"     %% "specs2-core"     % "3.6.4" % "test"
)

publishArtifact in Test := false

publishMavenStyle := false

bintrayOrganization := Some("fcomb")

licenses := Seq("MIT" -> url("http://www.opensource.org/licenses/mit-license.html"))
