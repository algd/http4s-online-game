name := "Http4sGameServer"

version in ThisBuild := "1.0"

scalaVersion in ThisBuild := "2.12.11"

lazy val shared = (crossProject(JSPlatform, JVMPlatform) in file("shared"))
  .settings (
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" %"0.13.0",
      "io.circe" %%% "circe-generic" %"0.13.0",
      "io.circe" %%% "circe-parser" % "0.13.0",
      "org.typelevel" %%% "cats-effect" % "2.1.3",
      "co.fs2" %%% "fs2-core" % "2.3.0"
    )
  )

lazy val server = (project in file("server"))
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-blaze-server" % "0.21.4",
      "org.http4s" %% "http4s-dsl" % "0.21.4"
    ),
    mainClass in assembly := Some("GameServer"),
    //(compile in Compile) := (compile in Compile).dependsOn(client / Compile / fastOptJS).value, // (compile in Compile).dependsOn(fastOptJS in client),
    Compile / resourceGenerators += Def.task {
      val allGeneratedFiles = ((resourceManaged in client in Compile).value ** "*") filter { _.isFile }
      val relativeFiles = allGeneratedFiles pair Path.rebase((resourceManaged in client in Compile).value, (resourceManaged in Compile).value / "public")
      relativeFiles.foreach {
        case (a, b) => println("Copying from " + a.getPath + " to " + b.getPath)
      }
      IO.copy(relativeFiles, true, false, false)
      relativeFiles.map(_._2)
    }.dependsOn(client / Compile / fastOptJS).taskValue/*,
    mappings in (Compile, packageBin) ++= {
      val allGeneratedFiles = ((resourceManaged in Compile).value ** "*") filter { _.isFile }
      allGeneratedFiles.get pair Path.relativeTo((resourceManaged in Compile).value)
    }*/
  )
  .dependsOn(shared.jvm)

lazy val client = (project in file("client"))
  .settings(
    //scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "1.0.0"
    ),
    artifactPath in fastOptJS in Compile :=
      (resourceManaged in Compile).value / ((moduleName in fastOptJS).value + ".js")
  )
  .dependsOn(shared.js)
  .enablePlugins(ScalaJSPlugin)

lazy val root = (project in file("."))
  .aggregate(
    shared.js,
    shared.jvm,
    server,
    client
  )