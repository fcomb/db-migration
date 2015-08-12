name := "db-migration"

organization := "io.fcomb"

version := "0.2.1"

crossScalaVersions := Seq("2.11.7", "2.10.5")

libraryDependencies ++= Seq(
  "org.slf4j"      %  "slf4j-api"   % "1.7.12",
  "org.specs2"     %% "specs2-core" % "3.6.4" % "test"
)

publishArtifact in Test := false

publishMavenStyle := false

bintrayOrganization := Some("fcomb")

licenses := Seq("MIT" -> url("http://www.opensource.org/licenses/mit-license.html"))
