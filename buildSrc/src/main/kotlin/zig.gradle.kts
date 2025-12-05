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
import de.undercouch.gradle.tasks.download.Download
import de.undercouch.gradle.tasks.download.Verify
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

plugins { id("de.undercouch.download") }

val buildInfo = extensions.create<BuildInfo>("buildInfo", project)

private val downloadFile
  get() = project.layout.buildDirectory.file("tmp/zig.$extension").get().asFile

private val extension: String
  get() = if (buildInfo.os.isWindows) "zip" else "tar.xz"

val downloadZig by
  tasks.registering(Download::class) {
    onlyIf { !buildInfo.zig.executable.exists() }
    doLast { println("Downloaded Zig to $downloadFile") }

    src(
      "https://ziglang.org/download/${buildInfo.zig.version}/zig-${buildInfo.os.canonicalName}-${buildInfo.arch.cName}-${buildInfo.zig.version}.$extension"
    )
    dest(downloadFile)
    overwrite(true)
  }

val verifyZig by
  tasks.registering(Verify::class) {
    dependsOn(downloadZig)
    src(downloadFile)
    onlyIf { !buildInfo.zig.executable.exists() }
    checksum(
      buildInfo.libs
        .findVersion("zigSha256-${buildInfo.os.canonicalName}-${buildInfo.arch.name}")
        .get()
        .toString()
    )
    algorithm("SHA-256")
  }

val installZig by
  tasks.registering {
    onlyIf { !buildInfo.zig.executable.exists() }
    dependsOn(verifyZig)

    doLast {
      buildInfo.zig.installDir.createDirectories()
      println("Extracting $downloadFile into ${buildInfo.zig.installDir}")
      // faster and more reliable than Gradle's `copy { from tarTree() }`
      providers
        .exec {
          workingDir = file(buildInfo.zig.installDir)
          executable = "tar"
          args("--strip-components=1", "-xf", downloadFile)
        }
        .result
        .get()
    }
  }
