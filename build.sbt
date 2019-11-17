val scala212 = "2.12.10"
val scala213 = "2.13.1"

name := "db-migration"

organization := "io.fcomb"

version := "0.6.3"

scalaVersion in ThisBuild := scala213

crossScalaVersions := Seq(scala212, scala213)

scalafmtOnCompile := true

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.21" % "provided"

bintrayOrganization := Some("fcomb")
bintrayRepository := "maven"

licenses := Seq("MIT" -> url("http://www.opensource.org/licenses/mit-license.html"))

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { _ =>
  false
}

pomExtra := (<url>https://github.com/fcomb/db-migration</url>
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

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "utf-8",
  "-explaintypes",
  "-feature",
  // "-release",
  // "11",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Xlint",
  "-Yrangepos",
  "-Ywarn-dead-code",
  "-Ywarn-extra-implicit",
  "-Ywarn-unused:_",
  "-Ywarn-value-discard"
)
