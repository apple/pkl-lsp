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
import org.gradle.internal.extensions.stdlib.capitalized
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated

plugins {
  application
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
  pklCli(libs.pklCli)
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

val treeSitterPklRepo = layout.buildDirectory.dir("repos/tree-sitter-pkl")
val treeSitterRepo = layout.buildDirectory.dir("repos/tree-sitter")

node {
  version = libs.versions.node
  nodeProjectDir = treeSitterPklRepo
  download = true
}

private val nativeLibDir = layout.buildDirectory.dir("native-lib")

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
      outputs.upToDateWhen {
        versionFile.get().asFile.let { file ->
          if (!file.exists()) {
            false
          } else {
            file.readText() == gitTagOrCommit.get()
          }
        }
      }
      doLast {
        exec {
          workingDir = repoDir.get().asFile
          commandLine("git", "fetch", "--tags", "origin")
        }
        exec {
          workingDir = repoDir.get().asFile
          commandLine("git", "checkout", gitTagOrCommit.get())
        }
      }
    }

  return tasks.register("setup$taskSuffix") {
    dependsOn(cloneTask)
    dependsOn(updateTask)
    outputs.dir(repoDir)
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
    treeSitterRepo,
  )

val setupTreeSitterPklRepo =
  configureRepo(
    "git@github.com:apple/tree-sitter-pkl",
    "treeSitterPkl",
    libs.versions.treeSitterPklRepo,
    treeSitterPklRepo,
  )

val makeTreeSitterLib by
  tasks.registering(Exec::class) {
    dependsOn(setupTreeSitterRepo)
    workingDir = treeSitterRepo.get().asFile
    inputs.dir(workingDir)

    val libraryName = System.mapLibraryName("tree-sitter")
    commandLine("make", libraryName)

    val outputFile = nativeLibDir.map { it.file(libraryName) }
    outputs.file(outputFile)

    doLast { workingDir.resolve(libraryName).renameTo(outputFile.get().asFile) }
  }

val npmInstallTreeSitter by
  tasks.registering(NpmInstallTask::class) {
    dependsOn(setupTreeSitterPklRepo)
    doFirst { workingDir = treeSitterPklRepo.get().asFile }
  }

val makeTreeSitterPklLib by
  tasks.registering(NodeTask::class) {
    dependsOn(npmInstallTreeSitter)
    inputs.dir(treeSitterPklRepo)
    doFirst { workingDir = treeSitterPklRepo.get().asFile }

    val libraryName = System.mapLibraryName("tree-sitter-pkl")

    val outputFile = nativeLibDir.map { it.file(libraryName) }

    script.set(treeSitterPklRepo.get().asFile.resolve("node_modules/.bin/tree-sitter"))
    args = listOf("build", "--output", outputFile.get().asFile.absolutePath)

    outputs.file(outputFile)
  }

tasks.processResources {
  dependsOn(makeTreeSitterLib)
  dependsOn(makeTreeSitterPklLib)
}

sourceSets { main { resources { srcDirs(nativeLibDir) } } }

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

/**
 * Builds a self-contained Pkl LSP CLI Jar that is directly executable on *nix and executable with
 * `java -jar` on Windows.
 *
 * For direct execution, the `java` command must be on the PATH.
 *
 * https://skife.org/java/unix/2011/06/20/really_executable_jars.html
 */
abstract class ExecutableJar : DefaultTask() {
  @get:InputFile abstract val inJar: RegularFileProperty

  @get:OutputFile abstract val outJar: RegularFileProperty

  @get:Input abstract val jvmArgs: ListProperty<String>

  @TaskAction
  fun buildJar() {
    val inFile = inJar.get().asFile
    val outFile = outJar.get().asFile
    val escapedJvmArgs = jvmArgs.get().joinToString(separator = " ") { "\"$it\"" }
    val startScript =
      """
            #!/bin/sh
            exec java $escapedJvmArgs -jar $0 "$@"
            """
        .trimIndent() + "\n\n\n"
    outFile.outputStream().use { outStream ->
      startScript.byteInputStream().use { it.copyTo(outStream) }
      inFile.inputStream().use { it.copyTo(outStream) }
    }

    // chmod a+x
    outFile.setExecutable(true, false)
  }
}
