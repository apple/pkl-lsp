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
@file:Suppress("MemberVisibilityCanBePrivate")

import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.getByType

// `buildInfo` in main build scripts
// `project.extensions.getByType<BuildInfo>()` in precompiled script plugins
open class BuildInfo(val project: Project) {
  val isCiBuild: Boolean by lazy { System.getenv("CI") != null }

  val isReleaseBuild: Boolean by lazy { java.lang.Boolean.getBoolean("releaseBuild") }

  val os: OperatingSystem by lazy {
    OperatingSystem.current()
  }

  /** The JDK version used to build the language server. */
  val jdkVersion: Int = 25

  /** The minimum JDK version required to run the language server. */
  val jdkTargetVersion: Int = 23

  val jvmTarget: Int = 23

  val arch: Architecture
    get() {
      return when (val arch = System.getProperty("os.arch")) {
        "x86_64", "amd64" -> Architecture.Amd64
        "aarch64", "arm64" -> Architecture.Aarch64
        else -> throw RuntimeException("Unsupported architecture: $arch")
      }
    }

  /** The target architecture for cross-compilation. */
  val targetArch: String by lazy { System.getProperty("pkl.targetArch", arch.name) }

  val isCrossArch: Boolean by lazy { targetArch != arch.name }

  val isCrossArchSupported: Boolean by lazy { os.isMacOsX }

  /** Whether to use musl libc for static linking (Alpine Linux). */
  val musl: Boolean by lazy { java.lang.Boolean.getBoolean("pkl.musl") }

  /** Whether to build with `-march=native` (optimized for build host). */
  val isNativeArch: Boolean by lazy { java.lang.Boolean.getBoolean("nativeArch") }

  // could be `commitId: Provider<String> = project.provider { ... }`
  val commitId: String by lazy {
    // only run command once per build invocation
    if (project === project.rootProject) {
      val process =
        ProcessBuilder()
          .command("git", "rev-parse", "--short", "HEAD")
          .directory(project.rootDir)
          .start()
      process.waitFor().also { exitCode ->
        if (exitCode == -1) throw RuntimeException(process.errorStream.reader().readText())
      }
      process.inputStream.reader().readText().trim()
    } else {
      project.rootProject.extensions.getByType(BuildInfo::class.java).commitId
    }
  }

  /** Synonym for [commitId] — used by CI scripts. */
  val commitish: String by lazy { commitId }

  val pklLspVersion: String by lazy {
    if (isReleaseBuild) {
      project.version.toString()
    } else {
      project.version.toString().replace("-SNAPSHOT", "-dev+$commitId")
    }
  }

  /** Version without commit ID — used for test assertions. */
  val pklLspVersionNonUnique: String by lazy {
    if (isReleaseBuild) {
      project.version.toString()
    } else {
      project.version.toString().replace("-SNAPSHOT", "-dev")
    }
  }

  // https://melix.github.io/blog/2021/03/version-catalogs-faq.html#_but_how_can_i_use_the_catalog_in_em_plugins_em_defined_in_code_buildsrc_code
  val libs: VersionCatalog by lazy {
    project.extensions.getByType<VersionCatalogsExtension>().named("libs")
  }

  val zig: Zig = Zig()

  inner class Zig {
    val version: String get() = libs.findVersion("zig").get().toString()

    val installDir: Path get() = project.projectDir.toPath().resolve(".gradle/zig/zig-${os.canonicalName}-${arch.name}-$version")

    val executable: Path get() = installDir.resolve(if (os.isWindows) "zig.exe" else "zig")
  }

  // --- GraalVM native image support ---

  val graalVmAmd64: GraalVm by lazy { createGraalVm("x64") }
  val graalVmAarch64: GraalVm by lazy { createGraalVm("aarch64") }

  private fun createGraalVm(arch: String): GraalVm {
    val osName = when {
      os.isMacOsX -> "macos"
      os.isLinux -> "linux"
      os.isWindows -> "windows"
      else -> throw RuntimeException("Unsupported OS for GraalVM: ${os.canonicalName}")
    }
    val version = libs.findVersion("graalVm").get().toString()
    return GraalVm(osName, arch, version)
  }

  inner class GraalVm(
    val osName: String,
    val arch: String,
    val version: String,
  ) {
    val graalVmJdkVersion: String = version

    val homeDir: File by lazy {
      File(System.getProperty("user.home"), ".graalvm")
    }
    val baseName: String by lazy { "graalvm-community-jdk-$graalVmJdkVersion-$osName-$arch" }

    /** Download URL from GraalVM CE GitHub releases. */
    val downloadUrl: String by lazy {
      val ext = if (os.isWindows) "zip" else "tar.gz"
      val platformArch = if (arch == "amd64") "x64" else arch
      "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-$version/graalvm-community-jdk-${version}_${osName}-${platformArch}_bin.$ext"
    }

    val downloadFile: File by lazy {
      project.rootDir.toPath().resolve(".gradle/graalvm/$baseName.${if (os.isWindows) "zip" else "tar.gz"}").toFile()
    }

    val installDir: File by lazy {
      homeDir.resolve(baseName)
    }

    val baseDir: File by lazy {
      if (os.isMacOsX) installDir.toPath().resolve("Contents/Home").toFile()
      else installDir
    }
  }

  init {
    if (!isReleaseBuild) {
      project.version = "${project.version}-SNAPSHOT"
    }
  }
}
