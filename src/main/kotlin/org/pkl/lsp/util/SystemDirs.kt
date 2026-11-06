/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.util

import java.nio.file.Path
import org.pkl.lsp.homeDir

/** Locations of Pkl's on-disk state; mirrors the XDG-style locations used by the Pkl CLI. */
object SystemDirs {
  val pklCacheDir: Path =
    when {
      OS.isWindows -> xdgCacheDir ?: windowsCacheDir ?: defaultCacheDir
      else -> xdgCacheDir ?: defaultCacheDir
    }

  val pklDataDir: Path =
    when {
      OS.isWindows -> xdgDataDir ?: windowsDataDir ?: defaultDataDir
      else -> xdgDataDir ?: defaultDataDir
    }

  val legacyPklCacheDir: Path = homeDir.resolve(".pkl/cache")

  private val windowsCacheDir: Path?
    get() = localAppData?.resolve("pkl/Cache")

  private val windowsDataDir: Path?
    get() = localAppData?.resolve("pkl/Data")

  private val localAppData: Path?
    get() = System.getenv("LOCALAPPDATA")?.asAbsolutePath

  private val xdgCacheDir: Path?
    get() = System.getenv("XDG_CACHE_HOME")?.asAbsolutePath?.resolve("pkl")

  private val xdgDataDir: Path?
    get() = System.getenv("XDG_DATA_HOME")?.asAbsolutePath?.resolve("pkl")

  private val defaultDataDir: Path
    get() = homeDir.resolve(".local/share/pkl")

  private val defaultCacheDir: Path
    get() = homeDir.resolve(".cache/pkl")

  // XDG spec says: ignore env var if the path is not absolute.
  // we'll do the same with LOCALAPPDATA.
  private val String.asAbsolutePath: Path?
    get() {
      return Path.of(this).takeIf(Path::isAbsolute)
    }
}
