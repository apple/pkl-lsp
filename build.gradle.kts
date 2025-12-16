/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.io.path.absolutePathString
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  application
  idea
  `maven-publish`
  signing
  zig
  alias(libs.plugins.kotlin)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.shadow)
  alias(libs.plugins.spotless)
  alias(libs.plugins.nexusPublish)
}

val buildInfo = project.extensions.getByType<BuildInfo>()

repositories { mavenCentral() }

java {
  sourceCompatibility = JavaVersion.toVersion(buildInfo.jdkTargetVersion)
  toolchain { languageVersion = JavaLanguageVersion.of(buildInfo.jdkVersion) }
}

kotlin {
  compilerOptions { jvmTarget = JvmTarget.fromTarget(buildInfo.jdkTargetVersion.toString()) }
}

tasks.withType<JavaCompile>().configureEach { options.release = buildInfo.jdkTargetVersion }

val pklCli: Configuration by configurations.creating

val stagedShadowJar: Configuration by configurations.creating

val pklStdlibFiles: Configuration by configurations.creating

val nativeLibDir = layout.buildDirectory.dir("generated/libs/native/")
val treeSitterPklRepoDir = layout.buildDirectory.dir("repos/tree-sitter-pkl")
val treeSitterRepoDir = layout.buildDirectory.dir("repos/tree-sitter")

val dummy: SourceSet by sourceSets.creating

@Suppress("unused")
val devLauncher: SourceSet by
  sourceSets.creating {
    // use test {compile,runtime}Classpath because it has the pkl stdlib
    // important that main output is first because of monkey-patched jtreesitter
    compileClasspath = sourceSets.main.get().output + sourceSets.test.get().compileClasspath
    runtimeClasspath =
      output + sourceSets.main.get().output + sourceSets.test.get().runtimeClasspath
  }

dependencies {
  implementation(kotlin("reflect"))
  implementation(libs.clikt)
  implementation(libs.lsp4j)
  implementation(libs.kotlinxSerializationJson)
  implementation(libs.jspecify) // used by jtreesitter
  implementation(libs.jtreesitter)
  implementation(libs.pklFormatter)
  // stdlib files are included from a one-off configuration then bundled into shadow jar (see
  // shadowJar spec).
  // declare a regular dependency for testing only.
  testRuntimeOnly(libs.pklStdlib)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation(libs.assertJ)
  testImplementation(libs.junitJupiter)
  testImplementation(libs.junitEngine)
  pklStdlibFiles(libs.pklStdlib)
  // comes from the attached workspace in CircleCI
  stagedShadowJar(tasks.shadowJar.get().outputs.files)
  pklCli(
    "org.pkl-lang:pkl-cli-${buildInfo.os.canonicalName}-${buildInfo.arch.name}:${libs.versions.pkl.get()}"
  )
}

val configurePklCliExecutable by
  tasks.registering { doLast { pklCli.singleFile.setExecutable(true) } }

tasks.jar {
  manifest {
    attributes("Main-Class" to "org.pkl.lsp.cli.Main", "Enable-Native-Access" to "ALL-UNNAMED")
  }
}

application { mainClass = "org.pkl.lsp.cli.Main" }

tasks.test {
  dependsOn(configurePklCliExecutable)
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  systemProperties["pklExecutable"] = pklCli.singleFile.absolutePath
  useJUnitPlatform { includeEngines("ParserSnippetTestEngine", "DiagnosticsSnippetTestEngine") }
  System.getProperty("testReportsDir")?.let { reportsDir ->
    reports.junitXml.outputLocation.set(file(reportsDir).resolve(project.name).resolve(name))
  }
}

tasks.distZip { dependsOn(tasks.shadowJar) }

tasks.distTar { dependsOn(tasks.shadowJar) }

tasks.named("startScripts") { dependsOn(tasks.shadowJar) }

tasks.named("startShadowScripts") { dependsOn(tasks.jar) }

val verifyShadowJar by
  tasks.registering {
    dependsOn(tasks.jar)
    dependsOn(tasks.shadowJar)
    inputs.files(tasks.shadowJar)
    group = "verification"
    doLast {
      val shadowJarFile = tasks.shadowJar.get().archiveFile.get().asFile
      val entries = zipTree(shadowJarFile).files.map { it.name }

      val nestedStdlibJars = entries.filter { it.matches(Regex("pkl-stdlib-.*\\.jar")) }
      if (nestedStdlibJars.isNotEmpty()) {
        throw GradleException("Shadow jar contains nested pkl-stdlib")
      }

      val pklFiles = entries.filter { it.endsWith(".pkl") }
      if (pklFiles.isEmpty()) {
        throw GradleException("Shadow jar does not contain any .pkl files")
      }
    }
  }

tasks.shadowJar {
  archiveClassifier = null
  // Ensure stdlib files are extracted into the shadow jar, rather than included as a `.jar` or
  // `.zip`
  from(pklStdlibFiles.map { zipTree(it) })
  finalizedBy(verifyShadowJar)
}

val javadocDummy by tasks.registering(Javadoc::class) { source = dummy.allJava }

// create a dummy javadoc jar to make maven central happy
val javadocJar by
  tasks.registering(Jar::class) {
    dependsOn(javadocDummy)
    archiveClassifier = "javadoc"
    from(javadocDummy.map { it.destinationDir })
  }

val sourcesJar by
  tasks.registering(Jar::class) {
    from(sourceSets.main.get().allSource)
    archiveClassifier = "sources"
  }

fun configureRepo(
  repo: String,
  simpleRepoName: String,
  gitTagOrCommit: Provider<String>,
  repoDir: Provider<Directory>,
): TaskProvider<Task> {
  val taskSuffix = simpleRepoName.capitalized() + "Repo"

  val cloneTask =
    tasks.register("clone$taskSuffix", Exec::class) {
      outputs.dir(repoDir)
      onlyIf { !repoDir.get().asFile.resolve(".git").exists() }
      commandLine("git", "clone", repo, repoDir.get().asFile)
    }

  val updateTask =
    tasks.register("update$taskSuffix") {
      outputs.dir(repoDir)
      dependsOn(cloneTask)
      inputs.property("gitTagOrCommit", gitTagOrCommit)
      doLast {
        providers
          .exec {
            workingDir = repoDir.get().asFile
            commandLine("git", "fetch", "--tags", "origin")
          }
          .result
          .get()
        providers
          .exec {
            workingDir = repoDir.get().asFile
            commandLine("git", "checkout", "-f", gitTagOrCommit.get())
          }
          .result
          .get()
      }
    }

  return tasks.register("setup$taskSuffix") {
    dependsOn(updateTask)
    outputs.dir(repoDir)
  }
}

val setupTreeSitterRepo =
  configureRepo(
    "https://github.com/tree-sitter/tree-sitter",
    "treeSitter",
    libs.versions.treeSitterRepo,
    treeSitterRepoDir,
  )

val setupTreeSitterPklRepo =
  configureRepo(
    "https://github.com/apple/tree-sitter-pkl",
    "treeSitterPkl",
    libs.versions.treeSitterPklRepo,
    treeSitterPklRepoDir,
  )

val oses by lazy {
  val macos = OperatingSystem.forName("osx")
  val linux = OperatingSystem.forName("linux")
  val windows = OperatingSystem.forName("windows")
  listOf(macos, linux, windows)
}

val architectures = listOf(Architecture.Amd64, Architecture.Aarch64)

val makeTreeSitterTasks: List<TaskProvider<*>> = buildList {
  for (os in oses) {
    for (arch in architectures) {
      val task =
        tasks.register(
          "makeTreeSitter${os.canonicalName.capitalized()}${arch.name.capitalized()}",
          Exec::class.java,
        ) {
          workingDir = treeSitterRepoDir.get().asFile
          dependsOn(setupTreeSitterRepo)
          configureCompile(
            os = os,
            arch = arch,
            libraryName = "tree-sitter",
            // flags taken from
            // https://github.com/tree-sitter/tree-sitter/blob/2a835ee029dca1c325e6f1c01dbce40396f6123e/Makefile#L28-L31
            includes = listOf("lib/include", "lib/src", "lib/src/wasm"),
            sources = listOf("lib/src/lib.c"),
            extraArgs =
              listOf(
                "-fvisibility=hidden",
                "-Wall",
                "-Wextra",
                "-Wpedantic",
                "-Werror=incompatible-pointer-types",
                "-D_POSIX_C_SOURCE=200112L",
                "-D_DEFAULT_SOURCE",
              ),
          )
        }
      add(task)
    }
  }
}

val makeTreeSitter by
  tasks.registering {
    group = "build"
    dependsOn(makeTreeSitterTasks)
  }

val makeTreeSitterPklTasks: List<TaskProvider<*>> = buildList {
  for (os in oses) {
    for (arch in architectures) {
      val task =
        tasks.register(
          "makeTreeSitterPkl${os.canonicalName.capitalized()}${arch.name.capitalized()}",
          Exec::class.java,
        ) {
          dependsOn(setupTreeSitterPklRepo)
          workingDir = treeSitterPklRepoDir.get().asFile

          configureCompile(
            os = os,
            arch = arch,
            libraryName = "tree-sitter-pkl",
            includes = listOf("src"),
            sources = listOf("src/parser.c", "src/scanner.c"),
          )
        }
      add(task)
    }
  }
}

val makeTreeSitterPkl by
  tasks.registering {
    group = "build"
    dependsOn(makeTreeSitterPklTasks)
  }

// Keep in sync with `org.pkl.lsp.treesitter.NativeLibrary.getResourcePath`
private fun resourceLibraryPath(os: OperatingSystem, arch: Architecture, libraryName: String) =
  "NATIVE/org/pkl/lsp/treesitter/${os.canonicalName}-${arch.name}/${os.getSharedLibraryName(libraryName)}"

private fun Exec.configureCompile(
  os: OperatingSystem,
  arch: Architecture,
  libraryName: String,
  includes: List<String>,
  sources: List<String>,
  extraArgs: List<String> = listOf(),
) {
  group = "build"

  dependsOn(tasks.named("installZig"))

  val outputFile = nativeLibDir.map { it.file(resourceLibraryPath(os, arch, libraryName)) }
  outputs.file(outputFile)
  for (dir in includes) {
    inputs.dir(workingDir.resolve(dir))
  }
  for (file in sources) {
    inputs.file(workingDir.resolve(file))
  }

  executable = buildInfo.zig.executable.absolutePathString()
  argumentProviders.add(
    CommandLineArgumentProvider {
      buildList {
        add("cc")
        add("-target")
        val targetFlagValue =
          if (os.isLinux) "${arch.cName}-linux-gnu" else "${arch.cName}-${os.canonicalName}"
        add(targetFlagValue)
        for (include in includes) {
          add("-I./$include")
        }
        for (source in sources) {
          add(source)
        }
        if (buildInfo.isReleaseBuild) {
          add("-O3")
        } else {
          add("-O0")
        }
        add("-std=c11")
        add("-shared")
        add("-fPIC")
        addAll(extraArgs)
        add("-o")
        add(outputFile.get().asFile.absolutePath)
      }
    }
  )
}

tasks.processResources {
  dependsOn(makeTreeSitter)
  dependsOn(makeTreeSitterPkl)
  filesMatching("org/pkl/lsp/Release.properties") {
    filter<ReplaceTokens>(
      "tokens" to
        mapOf(
          "version" to buildInfo.pklLspVersion,
          "treeSitterVersion" to libs.versions.treeSitterRepo.get(),
          "treeSitterPklVersion" to libs.versions.treeSitterPklRepo.get(),
        )
    )
  }
}

// verify the built distribution in different OSes.
@Suppress("unused")
val verifyDistribution by
  tasks.registering(Test::class) {
    dependsOn(configurePklCliExecutable)

    testClassesDirs = tasks.test.get().testClassesDirs
    classpath =
      sourceSets.test.get().output +
        stagedShadowJar +
        (configurations.testRuntimeClasspath.get() - configurations.runtimeClasspath.get())

    systemProperties["pklExecutable"] = pklCli.singleFile.absolutePath
    useJUnitPlatform()
    System.getProperty("testReportsDir")?.let { reportsDir ->
      reports.junitXml.outputLocation.set(file(reportsDir).resolve(project.name).resolve(name))
    }
  }

sourceSets { main { resources { srcDirs(nativeLibDir) } } }

private val licenseHeader =
  """
  /*
   * Copyright © ${'$'}YEAR Apple Inc. and the Pkl project authors. All rights reserved.
   *
   * Licensed under the Apache License, Version 2.0 (the "License");
   * you may not use this file except in compliance with the License.
   * You may obtain a copy of the License at
   *
   *     https://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing, software
   * distributed under the License is distributed on an "AS IS" BASIS,
   * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   * See the License for the specific language governing permissions and
   * limitations under the License.
   */
"""
    .trimIndent()

val originalRemoteName = System.getenv("PKL_ORIGINAL_REMOTE_NAME") ?: "origin"

spotless {
  ratchetFrom = "$originalRemoteName/main"
  kotlinGradle {
    ktfmt(libs.versions.ktfmt.get()).googleStyle()
    targetExclude("**/generated/**", "**/build/**")
    licenseHeader(licenseHeader, "([a-zA-Z]|@file|//)")
    target("*.kts", "buildSrc/**/*.kts")
  }
  kotlin {
    ktfmt(libs.versions.ktfmt.get()).googleStyle()
    licenseHeader(licenseHeader)
  }
}

nexusPublishing {
  repositories {
    sonatype {
      nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
      snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
  }
}

publishing {
  publications {
    create<MavenPublication>("pklLsp") {
      artifact(stagedShadowJar.singleFile) {
        classifier = null
        extension = "jar"
      }
      artifact(javadocJar.flatMap { it.archiveFile }) { classifier = "javadoc" }
      artifact(sourcesJar.flatMap { it.archiveFile }) { classifier = "sources" }
      pom {
        name.set("pkl-lsp")
        url.set("https://github.com/apple/pkl-lsp")
        description.set(
          """
          CLI for the Pkl Language Server.
          Requires Java ${buildInfo.jdkTargetVersion} or higher.
          """
            .trimIndent()
        )
        licenses {
          license {
            name = "Apache 2.0"
            url = "https://github.com/apple/pkl-lsp/blob/main/LICENSE.txt"
          }
        }
        developers {
          developer {
            id.set("pkl-authors")
            name.set("The Pkl Authors")
            email.set("pkl-oss@group.apple.com")
          }
        }
        scm {
          connection.set("scm:git:git://github.com/apple/pkl-lsp.git")
          developerConnection.set("scm:git:ssh://github.com/apple/pkl-lsp.git")
          url.set(
            "https://github.com/apple/pkl-lsp/tree/${if (buildInfo.isReleaseBuild) version else "main"}"
          )
        }
        issueManagement {
          system.set("GitHub Issues")
          url.set("https://github.com/apple/pkl-lsp/issues")
        }
        ciManagement {
          system.set("Circle CI")
          url.set("https://app.circleci.com/pipelines/github/apple/pkl-lsp")
        }
      }
    }
  }
}

signing {
  // provided as env vars `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword`
  // in CI.
  val signingKey =
    (findProperty("signingKey") as String?)?.let {
      Base64.getDecoder().decode(it).toString(StandardCharsets.US_ASCII)
    }
  val signingPassword = findProperty("signingPassword") as String?
  if (signingKey != null && signingPassword != null) {
    useInMemoryPgpKeys(signingKey, signingPassword)
  }
  sign(publishing.publications["pklLsp"])
}
