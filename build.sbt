// General

name := "project-doc"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.6"

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies ++= Seq(
  "org.apache.commons"   % "commons-compress" % "1.8.1",
  "commons-io"           % "commons-io"       % "2.4",
  "org.webjars"          % "foundation"       % "5.5.1",
  "com.googlecode.kiama" %% "kiama"           % "1.8.0",
  "io.spray"             %% "spray-caching"   % "1.3.3",
  "org.scalatest"        %% "scalatest"       % "2.2.4" % "test",
  "org.scalatestplus"    %% "play"            % "1.2.0" % "test",
  ws
)

// Play

routesGenerator := InjectedRoutesGenerator
routesImport += "controllers.Application.Project"

// sbt-web

JsEngineKeys.engineType := JsEngineKeys.EngineType.Node
StylusKeys.useNib in Assets := true
StylusKeys.compress in Assets := true

// Project/module declarations

lazy val root = (project in file(".")).enablePlugins(PlayScala)
