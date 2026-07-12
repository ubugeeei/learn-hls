ThisBuild / organization := "io.github.ubugeeei"
ThisBuild / scalaVersion := "3.7.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = project.in(file("."))
  .settings(
    name := "learn-hls",
    Compile / unmanagedSourceDirectories += baseDirectory.value / "examples",
    Compile / unmanagedSources / excludeFilter := HiddenFileFilter || "*Suite.scala",
    Test / unmanagedSourceDirectories := Seq(
      baseDirectory.value / "src" / "main" / "scala",
      baseDirectory.value / "examples"
    ),
    Test / unmanagedSources / includeFilter := "*Suite.scala",
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.4" % Test,
    Test / parallelExecution := true,
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all")
  )
