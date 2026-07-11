ThisBuild / organization := "io.github.ubugeeei"
ThisBuild / scalaVersion := "3.7.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = project.in(file("."))
  .settings(
    name := "learn-hls",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib"     % "0.11.5",
      "org.scalameta" %% "munit"    % "1.2.1" % Test
    ),
    Test / parallelExecution := true,
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all")
  )

