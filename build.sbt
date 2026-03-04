enablePlugins(BuildInfoPlugin)

//***************************
// CHANGE CHISEL VERSION HERE
//***************************
val fallbackChiselVersion = "7.9.0"
val supportedChiselVersions = Seq("7.9.0", "3.6.1")


val chiselVersion = sys.env.get("SOCT_CHISEL_VERSION") match {
  case Some(v) =>
    if (!supportedChiselVersions.contains(v)) {
      println(s"Warning: Unsupported Chisel version '$v'. Falling back to '$fallbackChiselVersion'. Supported versions are: ${supportedChiselVersions.mkString(", ")}.")
      fallbackChiselVersion
    } else {
      v
    }
  case None => fallbackChiselVersion
}

val useChisel3 = chiselVersion.startsWith("3.")

// NOTES:
// 22.04.2025: Don't add your own firtoolresolver as it will clash with the one used by chisel

lazy val chiselSettings = if (useChisel3) {
  Seq(
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full),
    libraryDependencies += "org.chipsalliance" %% "firtool-resolver" % "2.0.1",
    libraryDependencies += "edu.berkeley.cs" %% "chisel3" % chiselVersion,
    libraryDependencies += "edu.berkeley.cs" %% "chiseltest" % "0.6.0"
  )
} else {
  Seq(
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
    libraryDependencies += "org.chipsalliance" %% "chisel" % chiselVersion
  )
}

def freshProject(name: String, dir: File): Project = {
  Project(id = name, base = dir / "src")
    .settings(
      Compile / scalaSource := baseDirectory.value / "main" / "scala",
      Compile / resourceDirectory := baseDirectory.value / "main" / "resources",
      // Test:
      Test / scalaSource := baseDirectory.value / "test" / "scala",
      Test / resourceDirectory := baseDirectory.value / "test" / "resources",
    )
}

lazy val commonSettings = Seq(
  scalaVersion := (if (useChisel3) "2.13.14" else "2.13.18"),
  Global / parallelExecution := true,
  scalacOptions ++= Seq("-deprecation", "-Xcheckinit", "-unchecked", "-language:reflectiveCalls", "-feature", "-Ymacro-annotations"),
  libraryDependencies += "org.json4s" %% "json4s-jackson" % "4.0.7",
  libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.4.4",
  libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % (if (useChisel3) "3.9.5" else "3.9.6"), // For logging
  libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.20", // For logging backend
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  libraryDependencies += "org.antlr" % "antlr4" % "4.9.3" // Forced by firrtl
)

lazy val soct_org = Seq(organization := "soct")
lazy val berkeley_org = Seq(organization := "edu.berkeley.cs")

val rocketChipDir = file("generators/rocket-chip")

val diplomacyDir = if (useChisel3) {
  rocketChipDir / "dependencies/diplomacy-chisel3" // Separate branch for chisel3
} else {
  rocketChipDir / "dependencies/diplomacy"
}


// ------------------- Only chisel3/X compatible projects -------------------

// You can depend on this, and it will automatically depend on the correct versions of your projects
lazy val chiselProjects: Seq[ClasspathDep[ProjectReference]] = Seq()

// To avoid circular dependencies, you can add projects only the root project should depend on
lazy val chiselRootProjects: Seq[ClasspathDep[ProjectReference]] = Seq()

// ------------------- Fully compatible projects -------------------
lazy val compat = freshProject("compat", file("src/main/scala-unmanaged/compat"))
  .settings(soct_org)
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(
    // Lowering backend depends on the Chisel version
    Compile / unmanagedSourceDirectories ++= {
      if (useChisel3) Seq(file("src/main/scala-unmanaged/compat/chisel3"))
      else Seq(file("src/main/scala-unmanaged/compat/chisel"))
    }
  )

lazy val shuttle = freshProject("shuttle", file("generators/shuttle"))
  .settings(berkeley_org)
  .dependsOn(rocketchip)
  .settings(commonSettings)

lazy val saturn = freshProject("saturn-vectors", file("generators/saturn-vectors"))
  .settings(berkeley_org)
  .dependsOn(rocketchip, shuttle)
  .settings(commonSettings)

lazy val hardfloat = freshProject("hardfloat", rocketChipDir / "dependencies/hardfloat/hardfloat")
  .settings(berkeley_org)
  .settings(commonSettings)
  .settings(chiselSettings)

lazy val boom = freshProject("riscv-boom", file("generators/riscv-boom"))
  .dependsOn(cde)
  .dependsOn(rocketchip)
  .settings(commonSettings)
  .settings(chiselSettings)

lazy val rocketchip = freshProject("rocket-chip" + (if (useChisel3) "-chisel3" else ""), rocketChipDir)
  .settings(berkeley_org)
  .dependsOn(cde)
  .dependsOn(compat)
  .dependsOn(hardfloat)
  .dependsOn(diplomacy)
  .dependsOn(rocketMacros)
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(libraryDependencies ++= Seq("com.lihaoyi" %% "mainargs" % "0.7.7"))

lazy val gemmini = freshProject("gemmini", file("generators/gemmini"))
  .settings(berkeley_org)
  .dependsOn(cde)
  .dependsOn(compat)
  .dependsOn(rocketchip)
  .settings(commonSettings)
  .settings(chiselSettings)

lazy val rocketMacros = freshProject("rocket-macros", rocketChipDir / "macros")
  .settings(berkeley_org)
  .settings(commonSettings)
  .settings(chiselSettings)

// The CDE project is ill-formed, so we can't use freshProject here
lazy val cde = Project(id = "cde", base = rocketChipDir / "dependencies/cde")
  .settings(
    Compile / scalaSource := baseDirectory.value / "cde/src/chipsalliance/rocketchip",
    Test / scalaSource := baseDirectory.value / "cde/tests/src",
  )
  .settings(berkeley_org)
  .settings(commonSettings)
  .settings(chiselSettings)

// The Diplomacy project is ill-formed, so we can't use freshProject here
lazy val diplomacy = Project(id = "diplomacy", base = diplomacyDir)
  .settings(
    Compile / scalaSource := baseDirectory.value / "diplomacy/src",
  )
  .settings(berkeley_org)
  .dependsOn(cde)
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(Compile / scalaSource := baseDirectory.value / "diplomacy")

// The SiFive Rocket project is ill-formed, so we can't use freshProject here
lazy val sifiveCache = Project("sifive-cache", base = file("generators/sifive-cache"))
  .settings(
    Compile / scalaSource := baseDirectory.value / "design/craft/inclusivecache/src",
  )
  .dependsOn(cde)
  .dependsOn(rocketchip)
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(Compile / scalaSource := baseDirectory.value / "design/craft")

// ------------------- Root project -------------------
lazy val soceteer = (project in file("."))
  .settings(
    version := {
      val versionFile = baseDirectory.value / "VERSION"
      if (versionFile.exists()) {
        IO.read(versionFile).trim
      } else {
        "unknown"
      }
    })
  .settings(soct_org)
  .dependsOn(cde, rocketchip, sifiveCache, gemmini, boom, saturn)
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(
    assembly / assemblyOutputPath := baseDirectory.value / "target" / "assembly" / s"chisel-$chiselVersion" / s"soceteer-${version.value}.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "logback.xml") => MergeStrategy.first
      case PathList("META-INF", "logback-test.xml") => MergeStrategy.first
      case PathList("META-INF", "logging.properties") => MergeStrategy.first
      case PathList("META-INF", name) if name.toLowerCase.matches(""".*log4j.*\.xml""") =>
        MergeStrategy.first
      // Keep service loader metadata (needed for SLF4J, etc.)
      case PathList("META-INF", "services", _@_*) => MergeStrategy.concat
      // Discard manifest and cryptographic signatures
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs@_*) if xs.exists(_.matches(""".*\.(RSA|SF|DSA)$""")) => MergeStrategy.discard
      // Discard license/notice files
      case PathList("META-INF", "LICENSE" | "NOTICE" | "DEPENDENCIES" | "LICENSE.txt" | "NOTICE.txt") =>
        MergeStrategy.discard
      // Default safe fallback
      case _ => MergeStrategy.first
    }

  ).settings(
    // Lowering backend depends on the Chisel version
    Compile / unmanagedSourceDirectories ++= {
      if (useChisel3) Seq(baseDirectory.value / "src/main/scala-unmanaged/chisel3")
      else Seq(baseDirectory.value / "src/main/scala-unmanaged/chisel")
    }
  ).settings(name := "SoCeteer")


// Add build info settings to the root project
buildInfoPackage := "soct.build"

// Add SoCeteer name, version and Scala version to BuildInfo
buildInfoKeys := Seq[BuildInfoKey](
  name,
  version,
  scalaVersion,
  sbtVersion,
  "supportedChiselVersions" -> supportedChiselVersions.mkString(", ")
)