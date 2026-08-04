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
import java.lang.Runtime.Version
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider

plugins {
  id("pklGraalVm")
  id("pklNativeLifecycle")
}

// assumes that `pklJavaExecutable` is also applied
val executableSpec = project.extensions.getByType<ExecutableSpec>()
val buildInfo = project.extensions.getByType<BuildInfo>()

val nativeImageClasspath =
  configurations.create("nativeImageClasspath") {
    extendsFrom(project.configurations.getByName("runtimeClasspath"))
  }

private fun NativeImageBuild.amd64() {
  arch = GraalVmArchitecture.AMD64
  dependsOn(":installGraalVmAmd64")
}

private fun NativeImageBuild.aarch64() {
  arch = GraalVmArchitecture.AARCH64
  dependsOn(":installGraalVmAarch64")
}

private fun NativeImageBuild.setClasspath() {
  val mainSourceSet =
    project.extensions.getByType<JavaPluginExtension>().sourceSets.getByName("main")
  classpath.from(mainSourceSet.output)
  classpath.from(nativeImageClasspath)
}

val macExecutableAmd64 =
  tasks.register<NativeImageBuild>("macExecutableAmd64") {
    imageName = executableSpec.name.map { "$it-macos-amd64" }
    mainClass = executableSpec.mainClass
    amd64()
    setClasspath()
  }

val macExecutableAarch64 =
  tasks.register<NativeImageBuild>("macExecutableAarch64") {
    imageName = executableSpec.name.map { "$it-macos-aarch64" }
    mainClass = executableSpec.mainClass
    aarch64()
    setClasspath()
  }

val linuxExecutableAmd64 =
  tasks.register<NativeImageBuild>("linuxExecutableAmd64") {
    imageName = executableSpec.name.map { "$it-linux-amd64" }
    mainClass = executableSpec.mainClass
    amd64()
    setClasspath()
  }

val linuxExecutableAarch64 =
  tasks.register<NativeImageBuild>("linuxExecutableAarch64") {
    imageName = executableSpec.name.map { "$it-linux-aarch64" }
    mainClass = executableSpec.mainClass
    aarch64()
    setClasspath()
    extraNativeImageArgs.add("-H:PageSize=65536")
  }

val alpineExecutableAmd64 =
  tasks.register<NativeImageBuild>("alpineExecutableAmd64") {
    imageName = executableSpec.name.map { "$it-alpine-linux-amd64" }
    mainClass = executableSpec.mainClass
    amd64()
    setClasspath()
    extraNativeImageArgs.addAll(listOf("--static", "--libc=musl"))
  }

val windowsExecutableAmd64 =
  tasks.register<NativeImageBuild>("windowsExecutableAmd64") {
    imageName = executableSpec.name.map { "$it-windows-amd64" }
    mainClass = executableSpec.mainClass
    amd64()
    setClasspath()
  }

private fun <T : Task> Task.wraps(other: TaskProvider<T>) {
  dependsOn(other)
  outputs.files(other)
}

project.tasks.named("assembleNativeMacOsAarch64") { wraps(macExecutableAarch64) }

project.tasks.named("assembleNativeMacOsAmd64") { wraps(macExecutableAmd64) }

project.tasks.named("assembleNativeLinuxAarch64") { wraps(linuxExecutableAarch64) }

project.tasks.named("assembleNativeLinuxAmd64") { wraps(linuxExecutableAmd64) }

project.tasks.named("assembleNativeAlpineLinuxAmd64") { wraps(alpineExecutableAmd64) }

project.tasks.named("assembleNativeWindowsAmd64") { wraps(windowsExecutableAmd64) }

val assembleNative = project.tasks.named("assembleNative")

val testStartNativeExecutable =
  tasks.register("testStartNativeExecutable") {
    dependsOn(assembleNative)

    // dummy file for up-to-date checking
    val outputFile = project.layout.buildDirectory.file("testStartNativeExecutable/output.txt")
    outputs.file(outputFile)

    val execOutput =
      providers.exec { commandLine(assembleNative.get().outputs.files.singleFile, "--version") }

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

val requiredGlibcVersion: Version = Version.parse("2.17")

val checkGlibc =
  tasks.register("checkGlibc") {
    enabled = buildInfo.os.isLinux && !buildInfo.musl
    dependsOn(assembleNative)
    doLast {
      val exec =
        providers.exec {
          commandLine("objdump", "-T", assembleNative.get().outputs.files.singleFile)
        }
      val output = exec.standardOutput.asText.get()
      val minimumGlibcVersion =
        output
          .split("\n")
          .mapNotNull { line ->
            val match = Regex("GLIBC_([.0-9]*)").find(line)
            match?.groups[1]?.let { Version.parse(it.value) }
          }
          .maxOrNull()
      if (minimumGlibcVersion == null) {
        throw GradleException(
          "Could not determine glibc version from executable. objdump output: $output"
        )
      }
      if (minimumGlibcVersion > requiredGlibcVersion) {
        throw GradleException(
          "Incorrect glibc version. Found: $minimumGlibcVersion, required: $requiredGlibcVersion"
        )
      }
    }
  }

project.tasks.named("testNative") { dependsOn(testStartNativeExecutable, checkGlibc) }
