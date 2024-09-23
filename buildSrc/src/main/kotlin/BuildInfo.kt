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

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.internal.os.OperatingSystem
import java.nio.file.Path

// `buildInfo` in main build scripts
// `project.extensions.getByType<BuildInfo>()` in precompiled script plugins
open class BuildInfo(val project: Project) {
  val isCiBuild: Boolean by lazy { System.getenv("CI") != null }

  val isReleaseBuild: Boolean by lazy { java.lang.Boolean.getBoolean("releaseBuild") }

  val os: OperatingSystem by lazy {
    OperatingSystem.current()
  }

  val arch: Architecture
    get() {
      return when (val arch = System.getProperty("os.arch")) {
        "x86_64", "amd64" -> Architecture.Amd64
        "aarch64", "arm64" -> Architecture.Aarch64
        else -> throw RuntimeException("Unsupported architecture: $arch")
      }
    }

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

  val commitish: String by lazy { if (isReleaseBuild) project.version.toString() else commitId }

  val pklLspVersion: String by lazy {
    if (isReleaseBuild) {
      project.version.toString()
    } else {
      project.version.toString().replace("-SNAPSHOT", "-dev+$commitId")
    }
  }

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

    val installDir: Path get() = project.projectDir.toPath().resolve(".gradle/zig/zig-$version")

    val executable: Path get() = installDir.resolve(if (os.isWindows) "zig.exe" else "zig")
  }

  init {
    if (!isReleaseBuild) {
      project.version = "${project.version}-SNAPSHOT"
    }
  }
}
