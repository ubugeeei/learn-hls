ThisBuild / organization := "io.github.ubugeeei"
ThisBuild / scalaVersion := "3.7.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = project.in(file("."))
  .settings(
    name := "learn-hls",
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.4" % Test,
    Test / parallelExecution := true,
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all")
  )
