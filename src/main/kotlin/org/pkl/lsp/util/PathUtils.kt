/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import kotlin.io.path.*

/**
 * Utility functions for common path operations in the LSP implementation.
 */
object PathUtils {
  
  /**
   * Finds the common root directory for a list of paths.
   * Returns the deepest directory that contains all given paths.
   */
  fun findCommonRoot(paths: List<Path>): Path {
    if (paths.isEmpty()) return Path.of("")
    if (paths.size == 1) return paths[0] ?: Path.of("")

    // Remove empty paths, and make the rest absolute
    val absolutePaths = paths.filter { it.toString() != "" }.map { it.toAbsolutePath() }
    var commonRoot = absolutePaths[0] ?: return Path.of("")

    for (path in absolutePaths.drop(1)) {
      while (!path.startsWith(commonRoot)) {
        commonRoot = commonRoot.parent ?: return Path.of("")
      }
    }

    return commonRoot
  }
  
  /**
   * Finds all Pkl files (*.pkl and PklProject files) recursively in the given roots.
   * Handles both individual files and directories.
   */
  fun findPklFiles(roots: List<Path>): List<Path> {
    return roots.flatMap { root ->
      when {
        root.isRegularFile() && root.extension == "pkl" -> listOf(root)
        root.isDirectory() -> {
          @OptIn(ExperimentalPathApi::class)
          root
            .walk()
            .filter {
              it.isRegularFile() && (it.extension == "pkl" || it.fileName.name == "PklProject")
            }
            .toList()
        }
        else -> emptyList()
      }
    }
  }
  
  /**
   * Safely converts a path to absolute path with proper error handling.
   */
  fun Path.toAbsolutePathSafe(): Path = try {
    toAbsolutePath()
  } catch (e: Exception) {
    this
  }
  
  /**
   * Safely converts a path to real path (resolving symlinks) with proper error handling.
   */
  fun Path.toRealPathSafe(): Path = try {
    toRealPath()
  } catch (e: Exception) {
    toAbsolutePathSafe()
  }
}