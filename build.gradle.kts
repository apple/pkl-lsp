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
import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.task.NodeTask
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated

plugins {
  application
  idea
  alias(libs.plugins.kotlin)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.nodeGradle)
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

val buildInfo = extensions.create<BuildInfo>("buildInfo", project)

val jsitterMonkeyPatchSourceDir = layout.buildDirectory.dir("generated/libs/jtreesitter")
val nativeLibDir = layout.buildDirectory.dir("generated/libs/native/")
val treeSitterPklRepoDir = layout.buildDirectory.dir("repos/tree-sitter-pkl")
val treeSitterRepoDir = layout.buildDirectory.dir("repos/tree-sitter")

val osName
  get(): String {
    val os = OperatingSystem.current()
    return when {
      os.isMacOsX -> "macos"
      os.isLinux -> "linux"
      os.isWindows -> "windows"
      else -> throw RuntimeException("OS ${os.name} is not supported")
    }
  }

/** Same logic as [org.gradle.internal.os.OperatingSystem#arch], which is protected. */
val arch: String
  get() {
    return when (val arch = System.getProperty("os.arch")) {
      "x86" -> "i386"
      "x86_64" -> "amd64"
      "powerpc" -> "ppc"
      else -> arch
    }
  }

dependencies {
  implementation(kotlin("reflect"))
  implementation(libs.clikt)
  implementation(libs.pklCore)
  implementation(libs.lsp4j)
  implementation(libs.kotlinxSerializationJson)
  implementation(libs.jtreesitter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation(libs.assertJ)
  testImplementation(libs.junit.jupiter)
  jtreeSitterSources(variantOf(libs.jtreesitter) { classifier("sources") })
  pklCli("org.pkl-lang:pkl-cli-$osName-$arch:${libs.versions.pkl.get()}")
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

node {
  version = libs.versions.node
  nodeProjectDir = treeSitterPklRepoDir
  download = true
}

fun configureRepo(
  repo: String,
  simpleRepoName: String,
  gitTagOrCommit: Provider<String>,
  repoDir: Provider<Directory>,
): TaskProvider<Task> {
  val versionFile = layout.buildDirectory.file("tmp/repos/$simpleRepoName")
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
    dependsOn(cloneTask)
    dependsOn(updateTask)
    outputs.dir(repoDir)
    outputs.upToDateWhen {
      versionFile.get().asFile.let { it.exists() && it.readText() == gitTagOrCommit.get() }
    }
    doLast {
      versionFile.get().asFile.let { file ->
        file.ensureParentDirsCreated()
        file.writeText(gitTagOrCommit.get())
      }
    }
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

// Keep in sync with `org.pkl.lsp.treesitter.NativeLibrary.getResourcePath`
private fun resourceLibraryPath(libraryName: String) =
  "NATIVE/org/pkl/lsp/treesitter/$osName-$arch/$libraryName"

val makeTreeSitterLib by
  tasks.registering(Exec::class) {
    dependsOn(setupTreeSitterRepo)
    workingDir = treeSitterRepoDir.get().asFile
    inputs.dir(workingDir)

    val libraryName = System.mapLibraryName("tree-sitter")
    commandLine("make", libraryName)

    val outputFile = nativeLibDir.map { it.file(resourceLibraryPath(libraryName)) }
    outputs.file(outputFile)

    doLast { workingDir.resolve(libraryName).renameTo(outputFile.get().asFile) }
  }

val npmInstallTreeSitter by
  tasks.registering(NpmInstallTask::class) {
    dependsOn(setupTreeSitterPklRepo)
    doFirst { workingDir = treeSitterPklRepoDir.get().asFile }
  }

val makeTreeSitterPklLib by
  tasks.registering(NodeTask::class) {
    dependsOn(npmInstallTreeSitter)
    inputs.dir(treeSitterPklRepoDir)
    doFirst { workingDir = treeSitterPklRepoDir.get().asFile }

    val libraryName = System.mapLibraryName("tree-sitter-pkl")

    val outputFile = nativeLibDir.map { it.file(resourceLibraryPath(libraryName)) }

    script.set(treeSitterPklRepoDir.get().asFile.resolve("node_modules/.bin/tree-sitter"))
    args = listOf("build", "--output", outputFile.get().asFile.absolutePath)

    outputs.file(outputFile)
  }

tasks.processResources {
  dependsOn(makeTreeSitterLib)
  dependsOn(makeTreeSitterPklLib)
  // tree-sitter's CLI always generates debug symbols when on version 0.22.
  // we can remove this when tree-sitter-pkl upgrades the tree-sitter-cli dependency to 0.23 or
  // newer.
  exclude("**/*.dSYM/**")
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
