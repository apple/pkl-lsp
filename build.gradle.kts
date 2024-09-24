/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
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
import kotlin.io.path.absolutePathString
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.internal.os.OperatingSystem

plugins {
  application
  idea
  zig
  alias(libs.plugins.kotlin)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.shadow)
  alias(libs.plugins.spotless)
}

repositories { mavenCentral() }

java {
  sourceCompatibility = JavaVersion.VERSION_22
  toolchain { languageVersion = JavaLanguageVersion.of(22) }
}

val pklCli: Configuration by configurations.creating

val jtreeSitterSources: Configuration by configurations.creating

val jsitterMonkeyPatchSourceDir = layout.buildDirectory.dir("generated/libs/jtreesitter")
val nativeLibDir = layout.buildDirectory.dir("generated/libs/native/")
val treeSitterPklRepoDir = layout.buildDirectory.dir("repos/tree-sitter-pkl")
val treeSitterRepoDir = layout.buildDirectory.dir("repos/tree-sitter")

dependencies {
  implementation(kotlin("reflect"))
  implementation(libs.antlr)
  implementation(libs.clikt)
  implementation(libs.pklCore)
  implementation(libs.lsp4j)
  implementation(libs.kotlinxSerializationJson)
  implementation(libs.jtreesitter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation(libs.assertJ)
  testImplementation(libs.junit.jupiter)
  jtreeSitterSources(variantOf(libs.jtreesitter) { classifier("sources") })
  pklCli(
    "org.pkl-lang:pkl-cli-${buildInfo.os.canonicalName}-${buildInfo.arch.name}:${libs.versions.pkl.get()}"
  )
}

idea { module { generatedSourceDirs.add(jsitterMonkeyPatchSourceDir.get().asFile) } }

/**
 * jtreesitter expects the tree-sitter library to exist in system dirs, or to be provided through
 * `java.library.path`.
 *
 * This patches its source code so that we can control exactly where the tree-sitter library
 * resides.
 */
val monkeyPatchTreeSitter by
  tasks.registering(Copy::class) {
    from(zipTree(jtreeSitterSources.singleFile)) {
      include("**/TreeSitter.java")
      filter { line ->
        when {
          line.contains("static final SymbolLookup") ->
            "static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.libraryLookup(NativeLibraries.getTreeSitter().getLibraryPath(), LIBRARY_ARENA)"
          line.contains("package io.github.treesitter.jtreesitter.internal;") ->
            """
            $line
            
            import org.pkl.lsp.treesitter.NativeLibraries;
          """
              .trimIndent()
          else -> line
        }
      }
    }
    into(jsitterMonkeyPatchSourceDir)
  }

val configurePklCliExecutable by
  tasks.registering { doLast { pklCli.singleFile.setExecutable(true) } }

tasks.jar {
  manifest {
    attributes +=
      mapOf("Main-Class" to "org.pkl.lsp.cli.Main", "Enable-Native-Access" to "ALL-UNNAMED")
  }
}

application { mainClass.set("org.pkl.lsp.cli.Main") }

tasks.test {
  dependsOn(configurePklCliExecutable)
  systemProperties["pklExecutable"] = pklCli.singleFile.absolutePath
  systemProperties["java.library.path"] = nativeLibDir.get().asFile.absolutePath
  useJUnitPlatform()
  System.getProperty("testReportsDir")?.let { reportsDir ->
    reports.junitXml.outputLocation.set(file(reportsDir).resolve(project.name).resolve(name))
  }
}

tasks.shadowJar { archiveFileName.set("pkl-lsp") }

val javaExecutable by
  tasks.registering(ExecutableJar::class) {
    inJar.set(tasks.shadowJar.flatMap { it.archiveFile })
    outJar.set(layout.buildDirectory.file("executable/pkl-lsp"))

    // uncomment for debugging
    // jvmArgs.addAll("-ea", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
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
        exec {
          workingDir = repoDir.get().asFile
          commandLine("git", "fetch", "--tags", "origin")
        }
        exec {
          workingDir = repoDir.get().asFile
          commandLine("git", "checkout", "-f", gitTagOrCommit.get())
        }
      }
    }

  return tasks.register("setup$taskSuffix") {
    dependsOn(updateTask)
    outputs.dir(repoDir)
  }
}

val setupTreeSitterRepo =
  configureRepo(
    "git@github.com:tree-sitter/tree-sitter",
    "treeSitter",
    libs.versions.treeSitterRepo,
    treeSitterRepoDir,
  )

val setupTreeSitterPklRepo =
  configureRepo(
    "git@github.com:apple/tree-sitter-pkl",
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
            includes = listOf("lib/include", "lib/src"),
            sources = listOf("lib/src/lib.c"),
          )
        }
      add(task)
    }
  }
}

val makeTreeSitter by tasks.registering { dependsOn(*makeTreeSitterTasks.toTypedArray()) }

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

val makeTreeSitterPkl by tasks.registering { dependsOn(*makeTreeSitterPklTasks.toTypedArray()) }

// Keep in sync with `org.pkl.lsp.treesitter.NativeLibrary.getResourcePath`
private fun resourceLibraryPath(os: OperatingSystem, arch: Architecture, libraryName: String) =
  "NATIVE/org/pkl/lsp/treesitter/${os.canonicalName}-${arch.name}/${os.getSharedLibraryName(libraryName)}"

private fun Exec.configureCompile(
  os: OperatingSystem,
  arch: Architecture,
  libraryName: String,
  includes: List<String>,
  sources: List<String>,
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
        add("-Dtarget=${arch.cName}-${os.canonicalName}")
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

tasks.compileKotlin { dependsOn(monkeyPatchTreeSitter) }

sourceSets {
  main {
    java { srcDirs(jsitterMonkeyPatchSourceDir) }
    resources { srcDirs(nativeLibDir) }
  }
}

private val licenseHeader =
  """
  /**
   * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
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

spotless {
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
