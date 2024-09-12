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
@file:JvmName("Main")

package org.pkl.lsp.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun main(args: Array<String>) {
  extractSharedLibs()
  LspCommand().main(args)
}

// Java can't find shared libs inside a jar, so we have to extract it to CWD.
private fun extractSharedLibs() {
  // TODO: set this to `.pkl/yada`
  val libs = listOf("tree-sitter", "tree-sitter-pkl").map(System::mapLibraryName)
  libs.forEach { lib ->
    val stream =
      LspCommand::class.java.getResourceAsStream("/$lib")
        ?: throw RuntimeException("Could not find `$lib` library")
    stream.use { Files.copy(it, Path.of("./$lib"), StandardCopyOption.REPLACE_EXISTING) }
  }
}
