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
package org.pkl.lsp

import java.net.URI
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class Stdlib(project: Project) : Component(project) {
  @Suppress("MemberVisibilityCanBePrivate")
  val files: Map<String, VirtualFile> by lazy {
    val baseModuleUri = javaClass.getResource("/org/pkl/core/stdlib/base.pkl")!!.toURI()
    project.virtualFileManager.ensureJarFileSystem(baseModuleUri)
    val path = Path.of(baseModuleUri)
    path.parent
      .listDirectoryEntries()
      .filter { it.extension == "pkl" && it.name != "package-info.pkl" }
      .associate { file ->
        val name = file.name.replace(".pkl", "")
        logger.log("Found stdlib file: pkl:$name")
        name to project.virtualFileManager.get(URI("pkl:$name"))!!
      }
  }

  val base: VirtualFile
    get() = files["base"]!!
}
