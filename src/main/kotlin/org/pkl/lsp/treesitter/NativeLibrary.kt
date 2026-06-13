/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.treesitter

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.toPath
import org.pkl.lsp.Release
import org.pkl.lsp.homeDir
import org.pkl.lsp.util.OS

data class NativeLibrary(val name: String, val version: String) {
  companion object {
    // Keep in sync with the editor-support location in pkl-intellij. Unix lands native libs under
    // `~/.local/share/pkl/editor-support/native-libs`; Windows lands them under
    // `%LOCALAPPDATA%/pkl/editor-support/native-libs`. Existing installs keep using
    // `~/.pkl/editor-support/native-libs` (so an upgrade doesn't trigger a re-download). The lazy
    // initialiser is fine here – native libs don't move during a session.
    private val nativeLibsDir by lazy {
      val newLocation: Path? =
        if (OS.isWindows) {
          System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it, "pkl", "editor-support", "native-libs") }
        } else {
          homeDir.resolve(".local/share/pkl/editor-support/native-libs")
        }
      val legacy = homeDir.resolve(".pkl/editor-support/native-libs")
      when {
        newLocation != null && Files.exists(newLocation) -> newLocation
        Files.exists(legacy) -> legacy
        else -> newLocation ?: legacy
      }
    }
  }

  private val systemLibraryName = System.mapLibraryName(name)

  private val resourcePath: URL by lazy {
    // keep in sync with `resourceLibraryPath` in build.gradle.kts
    val path = "/NATIVE/org/pkl/lsp/treesitter/${OS.name}-${OS.arch}/$systemLibraryName"
    NativeLibrary::class.java.getResource(path)!!
  }

  private val storedLibraryPath: Path by lazy {
    nativeLibsDir.resolve("$name/$version/$systemLibraryName")
  }

  val libraryPath: Path by lazy {
    when {
      // optimization: if the resource file is a normal file, we can use it directly.
      resourcePath.protocol == "file" -> resourcePath.toURI().toPath()
      storedLibraryPath.exists() -> storedLibraryPath
      else -> {
        storedLibraryPath.createParentDirectories()
        resourcePath.openStream().use { Files.copy(it, storedLibraryPath) }
        storedLibraryPath
      }
    }
  }
}

object NativeLibraries {
  val treeSitter = NativeLibrary("tree-sitter", Release.treeSitterVersion)

  val treeSitterPkl = NativeLibrary("tree-sitter-pkl", Release.treeSitterPklVersion)
}
