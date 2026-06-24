/*
 * Copyright © 2025-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.toolchain.JavaLauncher

plugins {
  // shadow plugin applied by root build.gradle.kts; accessed via string-based lookups
}

val executableSpec = project.extensions.create("executable", ExecutableSpec::class.java)
val buildInfo = project.extensions.getByType<BuildInfo>()

val shadowJarTask = project.tasks.named("shadowJar")

val javaExecutable by
  tasks.registering(ExecutableJar::class) {
    group = "build"
    dependsOn(project.tasks.named("jar"))
    inJar = shadowJarTask.flatMap { (it as AbstractArchiveTask).archiveFile }
    val effectiveJavaName =
      executableSpec.javaName.map { name -> if (buildInfo.os.isWindows) "$name.bat" else name }
    outJar = layout.buildDirectory.dir("executable").flatMap { it.file(effectiveJavaName) }
  }

fun Task.setupTestStartJavaExecutable(launcher: Provider<JavaLauncher>? = null) {
  group = "verification"
  dependsOn(javaExecutable)

  val outputFile = layout.buildDirectory.file("testStartJavaExecutable/$name")
  outputs.file(outputFile)

  val execOutput =
    providers.exec {
      val executablePath = javaExecutable.get().outputs.files.singleFile
      if (launcher?.isPresent == true) {
        commandLine(
          launcher.get().executablePath.asFile.absolutePath,
          "-jar",
          executablePath.absolutePath,
          "--version",
        )
      } else {
        commandLine(executablePath.absolutePath, "--version")
      }
    }

  doLast {
    val outputText = execOutput.standardOutput.asText.get()
    if (!outputText.contains(buildInfo.pklLspVersionNonUnique)) {
      throw GradleException(
        "Expected version output to contain current version (${buildInfo.pklLspVersionNonUnique}), but got '$outputText'"
      )
    }
    outputFile.get().asFile.toPath().apply {
      try {
        parent.createDirectories()
      } catch (_: java.nio.file.FileAlreadyExistsException) {}
      writeText("OK")
    }
  }
}

val testStartJavaExecutable by tasks.registering { setupTestStartJavaExecutable() }

project.tasks.named("assemble") { dependsOn(javaExecutable) }

project.tasks.named("check") { dependsOn(testStartJavaExecutable) }
