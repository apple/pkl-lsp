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
package org.pkl.lsp

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@OptIn(ExperimentalPathApi::class)
class RepositoryHygiene {
  @Test
  fun `no remaining snippet test selection`() {
    assertThat(DiagnosticsSnippetTestEngine().selection).isEqualTo("")
  }

  @ParameterizedTest()
  @MethodSource("getSnippetFolders")
  fun `no output files exists for snippets without an input`(snippetsFolder: Path) {
    val input = snippetsFolder.resolve("input")
    val inputs =
      input
        .walk()
        .filter { it.extension == "pkl" }
        .map {
          val path = input.relativize(it).toString()
          inputRegex.replace(path, "$1$2")
        }
        .toSet()

    val output = snippetsFolder.resolve("output")
    output
      .walk()
      .filter { it.isRegularFile() }
      .forEach {
        val out = output.relativize(it).toString()
        checkOutputHasInput(inputs, out)
      }
  }

  private fun checkOutputHasInput(inputs: Set<String>, output: String) {
    val fileToCheck = outputRegex.replace(output, "$1.pkl")
    assertThat(inputs).contains(fileToCheck)
  }

  companion object {
    private val inputRegex = Regex("(.*)\\.[^.]*(\\.pkl)")
    private val outputRegex = Regex("(.*)\\.[^.]+\\.txt$")

    @JvmStatic
    fun getSnippetFolders() = buildList {
      add(FileTestUtils.rootProjectDir.resolve("src/test/files/DiagnosticsSnippetTests"))
      add(FileTestUtils.rootProjectDir.resolve("src/test/files/ParserSnippetTests"))
    }
  }
}
