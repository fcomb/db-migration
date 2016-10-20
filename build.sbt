name := "db-migration"

organization := "io.fcomb"

version := "0.3.3-RC1"

scalaVersion in ThisBuild := "2.11.8"

crossScalaVersions := Seq("2.12.0-RC2", "2.11.8")

libraryDependencies ++= Seq(
  "org.slf4j"      %  "slf4j-api"   % "1.7.21",
  "org.specs2"     %% "specs2-core" % "3.8.5.1" % "test"
)

bintrayOrganization := Some("fcomb")

licenses := Seq("MIT" -> url("http://www.opensource.org/licenses/mit-license.html"))

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

pomExtra := (
  <url>https://github.com/fcomb/db-migration</url>
  <licenses>
    <license>
      <name>MIT License</name>
      <url>http://www.opensource.org/licenses/mit-license.html</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:fcomb/db-migration.git</url>
    <connection>scm:git:git@github.com:fcomb/db-migration.git</connection>
  </scm>
  <developers>
    <developer>
      <id>fcomb</id>
      <name>fcomb</name>
      <url>https://github.com/fcomb/</url>
    </developer>
  </developers>)
