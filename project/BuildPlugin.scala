package build

import sbt.{AutoPlugin, Command, Def, Keys, PluginTrigger, Plugins}

object BuildPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  import com.typesafe.sbt.SbtPgp
  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin
  import com.lucidchart.sbt.scalafmt.ScalafmtCorePlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins =
    JvmPlugin && ScalafmtCorePlugin && ReleaseEarlyPlugin && SbtPgp
  val autoImport = BuildKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    BuildImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    BuildImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    BuildImplementation.projectSettings
}

object BuildKeys {
  import sbt.{LocalProject, Reference, RootProject, ProjectRef, BuildRef, file}
  import sbt.librarymanagement.syntax.stringToOrganization
  def inProject(ref: Reference)(ss: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] =
    sbt.inScope(sbt.ThisScope.in(project = ref))(ss)

  def inProjectRefs(refs: Seq[Reference])(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    refs.flatMap(inProject(_)(ss))

  def inCompileAndTest(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    Seq(sbt.Compile, sbt.Test).flatMap(sbt.inConfig(_)(ss))

  // Use absolute paths so that references work even if `ThisBuild` changes
  final val AbsolutePath = file(".").getCanonicalFile.getAbsolutePath

  final val ZincProject = RootProject(file(s"$AbsolutePath/zinc"))
  final val ZincBuild = BuildRef(ZincProject.build)
  final val Zinc = ProjectRef(ZincProject.build, "zinc")
  final val ZincRoot = ProjectRef(ZincProject.build, "zincRoot")
  final val ZincBridge = ProjectRef(ZincProject.build, "compilerBridge")

  final val NailgunProject = RootProject(file(s"$AbsolutePath/nailgun"))
  final val NailgunBuild = BuildRef(NailgunProject.build)
  final val Nailgun = ProjectRef(NailgunProject.build, "nailgun")
  final val NailgunServer = ProjectRef(NailgunProject.build, "nailgun-server")
  final val NailgunExamples = ProjectRef(NailgunProject.build, "nailgun-examples")

  final val BenchmarkBridgeProject = RootProject(file(s"$AbsolutePath/benchmark-bridge"))
  final val BenchmarkBridgeBuild = BuildRef(BenchmarkBridgeProject.build)
  final val BenchmarkBridgeCompilation = ProjectRef(BenchmarkBridgeProject.build, "compilation")

  import sbt.{Test, TestFrameworks, Tests}
  import sbt.io.syntax.fileToRichFile
  val buildBase = Keys.baseDirectory in sbt.ThisBuild
  val integrationTestsLocation = Def.settingKey[sbt.File]("Where to find the integration tests")
  val scriptedAddSbtBloop = Def.taskKey[Unit]("Add sbt-bloop to the test projects")
  val latestTag = Def.settingKey[String]("Latest tag of Bloop")
  val bloopShellCmdName = Def.settingKey[String]("Name of the `bloop-shell` command")
  val bloopServerCmdName = Def.settingKey[String]("Name of the `bloop-server` command")
  val bloopNgClientCmdName = Def.settingKey[String]("Name of bloop's NG client command")
  val testSettings: Seq[Def.Setting[_]] = List(
    integrationTestsLocation := buildBase.value / "integration-tests" / "integration-projects",
    Keys.testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    Keys.libraryDependencies ++= List(
      Dependencies.junit % Test
    ),
  )

  import sbtbuildinfo.BuildInfoKey
  final val BloopInfoKeys = {
    val zincVersion = Keys.version in ZincRoot
    val zincKey = BuildInfoKey.map(zincVersion) { case (k, version) => "zincVersion" -> version }
    val developersKey = BuildInfoKey.map(Keys.developers) {
      case (k, devs) => k -> devs.map(_.name)
    }
    val commonKeys = List[BuildInfoKey](Keys.name,
                                        Keys.version,
                                        Keys.scalaVersion,
                                        Keys.sbtVersion,
                                        integrationTestsLocation,
                                        bloopNgClientCmdName)
    commonKeys ++ List(zincKey, developersKey)
  }

  import sbtassembly.AssemblyKeys
  val assemblySettings: Seq[Def.Setting[_]] = List(
    Keys.mainClass in AssemblyKeys.assembly := Some("bloop.Bloop"),
    Keys.test in AssemblyKeys.assembly := {}
  )

  import sbtbuildinfo.BuildInfoKeys
  def benchmarksSettings(project: Reference): Seq[Def.Setting[_]] = List(
    Keys.skip in Keys.publish := true,
    BuildInfoKeys.buildInfoKeys := Seq[BuildInfoKey](Keys.resourceDirectory in sbt.Test in project),
    BuildInfoKeys.buildInfoPackage := "bloop.benchmarks",
    Keys.javaOptions ++= {
      def refOf(version: String) = {
        val HasSha = """([0-9a-f]{8})(?:\+\d{8}-\d{4})?""".r
        version match {
          case HasSha(sha) => sha
          case _ => version
        }
      }
      List(
        "-Dsbt.launcher=" + (sys
          .props("java.class.path")
          .split(java.io.File.pathSeparatorChar)
          .find(_.contains("sbt-launch"))
          .getOrElse("")),
        "-DbloopVersion=" + Keys.version.in(project).value,
        "-DbloopRef=" + refOf(Keys.version.in(project).value),
        "-Dbloop.jar=" + AssemblyKeys.assembly.in(project).value,
        "-Dgit.localdir=" + buildBase.value.getAbsolutePath
      )
    }
  )

  final val templatesSettings: Seq[Def.Setting[_]] = Seq(
    TemplatePlugin.variableMappings := Map(
      "NAILGUN_COMMIT" -> GitUtils.commitIn(GitUtils.submoduleDir("nailgun").value),
      "BLOOP_LATEST_TAG" -> BuildKeys.latestTag.value,
      "BLOOP_LATEST_RELEASE" -> (BuildKeys.latestTag.value match {
        case tag if tag.startsWith("v") => tag.tail
        case tag => tag
      }),
      "BLOOP_SERVER_CMD" -> bloopServerCmdName.value,
      "BLOOP_SHELL_CMD" -> bloopShellCmdName.value,
      "BLOOP_CLIENT_CMD" -> bloopNgClientCmdName.value,
      "BLOOP_INSTALL_PY_SHA256" -> TemplatePlugin.Lazy(
        sha256(buildBase.value / "bin" / "install.py").getOrElse("file-doesnt-exist"))
    ),
    TemplatePlugin.templateMappings := Map(
      buildBase.value / "templates" / "install.py" -> buildBase.value / "bin" / "install.py",
      buildBase.value / "templates" / "bloop.rb" -> buildBase.value / "homebrew" / "bloop.rb",
      buildBase.value / "templates" / "README.md" -> buildBase.value / "README.md"
    )
  )

  private def sha256(file: sbt.File): Option[String] = {
    import java.nio.file.Files
    import java.security.MessageDigest
    scala.util.Try {
      val digest = MessageDigest.getInstance("SHA-256")
      val bytes = Files.readAllBytes(file.toPath)
      val hash = digest.digest(bytes)
      val hexString = new StringBuilder()
      hash.foreach { byte =>
        val hex = Integer.toHexString(0xff & byte)
        if (hex.length == 1) hexString.append('0')
        else hexString.append(hex)
      }

      hexString.toString
    }.toOption

  }

}

object BuildImplementation {
  import sbt.{url, file}
  import sbt.{Developer, Resolver, Watched, Compile, Test}

  // This should be added to upstream sbt.
  def GitHub(org: String, project: String): java.net.URL =
    url(s"https://github.com/$org/$project")
  def GitHubDev(handle: String, fullName: String, email: String) =
    Developer(handle, fullName, email, url(s"https://github.com/$handle"))

  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin.{autoImport => ReleaseEarlyKeys}

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val buildPublishSettings: Seq[Def.Setting[_]] = Seq(
    Keys.startYear := Some(2017),
    Keys.autoAPIMappings := true,
    Keys.publishMavenStyle := true,
    Keys.homepage := Some(ThisRepo),
    Keys.licenses := Seq("BSD" -> url("http://opensource.org/licenses/BSD-3-Clause")),
    Keys.developers := List(
      GitHubDev("Duhemm", "Martin Duhem", "martin.duhem@gmail.com"),
      GitHubDev("jvican", "Jorge Vicente Cantero", "jorge@vican.me")
    ),
    ReleaseEarlyKeys.releaseEarlyWith := ReleaseEarlyKeys.SonatypePublisher,
  )

  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    Keys.testOptions in Test += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    Keys.commands ~= BuildDefaults.fixPluginCross _,
    Keys.onLoad := BuildDefaults.onLoad.value,
    Keys.publishArtifact in Test := false,
    BuildKeys.latestTag := GitUtils.latestTagIn(BuildKeys.buildBase.value),
    BuildKeys.bloopShellCmdName := "bloop-shell",
    BuildKeys.bloopServerCmdName := "bloop-server",
    BuildKeys.bloopNgClientCmdName := "bloop"
  )

  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
    Keys.resolvers += Resolver.jcenterRepo,
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := "2.12.4",
    Keys.triggeredMessage := Watched.clearWhenTriggered,
  ) ++ buildPublishSettings

  final val projectSettings: Seq[Def.Setting[_]] = Seq(
    Keys.scalacOptions in Compile := reasonableCompileOptions,
  )

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: Nil
  )

  final val jvmOptions = "-Xmx4g" :: "-Xms2g" :: Nil

  object BuildDefaults {
    import sbt.State
    /* This rounds off the trickery to set up those projects whose `overridingProjectSettings` have
     * been overriden because sbt has decided to initialize the settings from the sourcedep after. */
    val hijacked = sbt.AttributeKey[Boolean]("The hijacked option.")
    val onLoad: Def.Initialize[State => State] = Def.setting { (state: State) =>
      val globalSettings =
        List(Keys.onLoadMessage in sbt.Global := s"Setting up the integration builds.")
      def genProjectSettings(ref: sbt.ProjectRef) =
        BuildKeys.inProject(ref)(List(Keys.organization := "ch.epfl.scala"))
      val buildStructure = sbt.Project.structure(state)
      if (state.get(hijacked).getOrElse(false)) state.remove(hijacked)
      else {
        val hijackedState = state.put(hijacked, true)
        val extracted = sbt.Project.extract(hijackedState)
        val allZincProjects = buildStructure.allProjectRefs(BuildKeys.ZincBuild.build)
        val allNailgunProjects = buildStructure.allProjectRefs(BuildKeys.NailgunBuild.build)
        val allBenchmarkBridgeProjects =
          buildStructure.allProjectRefs(BuildKeys.BenchmarkBridgeBuild.build)
        val allProjects = allZincProjects ++ allNailgunProjects ++ allBenchmarkBridgeProjects
        val projectSettings = allProjects.flatMap(genProjectSettings)
        // NOTE: This is done because sbt does not handle session settings correctly. Should be reported upstream.
        val currentSession = sbt.Project.session(state)
        val currentProject = currentSession.current
        val currentSessionSettings =
          currentSession.append.get(currentProject).toList.flatten.map(_._1)
        val allSessionSettings = currentSessionSettings ++ currentSession.rawAppend
        extracted.append(globalSettings ++ projectSettings ++ allSessionSettings, hijackedState)
      }
    }

    def fixPluginCross(commands: Seq[Command]): Seq[Command] = {
      val pruned = commands.filterNot(p => p == sbt.WorkingPluginCross.oldPluginSwitch)
      sbt.WorkingPluginCross.pluginSwitch +: pruned
    }

    import java.io.File
    import sbt.io.{AllPassFilter, IO}
    import sbt.ScriptedPlugin.{autoImport => ScriptedKeys}

    private def createScriptedSetup(testDir: File) = {
      s"""
         |bloopConfigDir in Global := file("$testDir/bloop-config")
         |TaskKey[Unit]("registerDirectory") := {
         |  val dir = (baseDirectory in ThisBuild).value
         |  IO.write(file("$testDir/bloop-config/base-directory"), dir.getAbsolutePath)
         |}
         |TaskKey[Unit]("checkInstall") := {
         |  Thread.sleep(1000) // Let's wait a little bit because of OS's IO latency
         |  val mostRecentStamp = (bloopConfigDir.value ** "*.config").get.map(_.lastModified).min
         |  (System.currentTimeMillis() - mostRecentStamp)
         |  val diff = (System.currentTimeMillis() - mostRecentStamp) / 1000
         |  if (diff <= 5 * 60) () // If it happened in the last 5 minutes, this is ok!
         |  else sys.error("The sbt plugin didn't write any file")
         |}
    """.stripMargin
    }

    private val scriptedTestContents = {
      """> show bloopConfigDir
        |# Some projects need to compile to generate resources. We do it now so that the configuration
        |# that we generate doesn't appear too old.
        |> resources
        |> registerDirectory
        |> installBloop
        |> checkInstall
        |> copyContentOutOfScripted
      """.stripMargin
    }

    import sbt.librarymanagement.Artifact
    import ch.epfl.scala.sbt.maven.MavenPluginKeys
    val mavenPluginBuildSettings: Seq[Def.Setting[_]] = List(
      MavenPluginKeys.mavenPlugin := true,
      Keys.publishLocal := Keys.publishM2.value,
      Keys.classpathTypes += "maven-plugin",
      // This is a bug in sbt, so we fix it here.
      Keys.makePomConfiguration :=
        Keys.makePomConfiguration.value.withIncludeTypes(Keys.classpathTypes.value),
      Keys.libraryDependencies ++= List(
        Dependencies.mavenCore,
        Dependencies.mavenPluginApi,
        Dependencies.mavenPluginAnnotations,
        // We add an explicit dependency to the maven-plugin artifact in the dependent plugin
        Dependencies.mavenScalaPlugin
          .withExplicitArtifacts(Vector(Artifact("scala-maven-plugin", "maven-plugin", "jar")))
      ),
    )

    val fixScalaVersionForSbtPlugin: Def.Initialize[String] = Def.setting {
      val orig = Keys.scalaVersion.value
      val is013 = (Keys.sbtVersion in Keys.pluginCrossBuild).value.startsWith("0.13")
      if (is013) "2.10.6" else orig
    }

    private val NewLine = System.lineSeparator
    import sbt.io.syntax.{fileToRichFile, singleFileFinder}
    val scriptedSettings: Seq[Def.Setting[_]] = List(
      ScriptedKeys.scriptedBufferLog := true,
      ScriptedKeys.sbtTestDirectory := (Keys.baseDirectory in sbt.ThisBuild).value / "integration-tests",
      BuildKeys.scriptedAddSbtBloop := {
        val addSbtPlugin =
          s"""addSbtPlugin("${Keys.organization.value}" % "${Keys.name.value}" % "${Keys.version.value}")$NewLine"""
        val testPluginSrc = Keys.baseDirectory
          .in(sbt.ThisBuild)
          .value / "project" / "TestPlugin.scala"
        val tests =
          (ScriptedKeys.sbtTestDirectory.value / "integration-projects").*(AllPassFilter).get
        tests.foreach { testDir =>
          IO.copyFile(testPluginSrc, testDir / "project" / "TestPlugin.scala")
          IO.createDirectory(testDir / "bloop-config")
          IO.write(testDir / "project" / "test-config.sbt", addSbtPlugin)
          IO.write(testDir / "test-config.sbt", createScriptedSetup(testDir))
          IO.write(testDir / "test", scriptedTestContents)
        }
      },
    )
  }
}

object Header {
  val intro: String =
    """      _____            __         ______           __
      |     / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____
      |     \__ \/ ___/ __ `/ / __ `/  / /   / _ \/ __ \/ __/ _ \/ ___/
      |    ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /
      |   /____/\___/\__,_/_/\__,_/   \____/\___/_/ /_/\__/\___/_/
      |
      |   ***********************************************************
      |   ***       Welcome to the build of `loooooooooop`        ***
      |   ***        An effort funded by the Scala Center         ***
      |   ***********************************************************
    """.stripMargin
}

private object GitUtils {

  import org.eclipse.jgit.api.Git
  import org.eclipse.jgit.storage.file.FileRepositoryBuilder
  import org.eclipse.jgit.lib.ObjectId
  import sbt.io.syntax.fileToRichFile

  def commitIn(dir: sbt.File): String = {
    val repository =
      new FileRepositoryBuilder().setGitDir(dir).readEnvironment().findGitDir().build()
    val head = repository.resolve("HEAD")
    ObjectId.toString(head)
  }

  def latestTagIn(dir: sbt.File): String = {
    val git = Git.open(dir)
    Option(git.describe().call()).getOrElse("no-tag-yet")
  }

  def submoduleDir(name: String) = Def.setting {
    BuildKeys.buildBase.value / ".git" / "modules" / name
  }

}
