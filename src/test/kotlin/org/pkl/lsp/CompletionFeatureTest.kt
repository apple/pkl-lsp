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
package org.pkl.lsp

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pkl.lsp.completion.ModuleUriCompletionProvider.Companion.ModuleUriCompletionData

class CompletionFeatureTest : LspTestBase() {
  @Test
  fun `complete amends header`() {
    createPklFile("amends \"<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label })
      .isEqualTo(listOf("pkl:", "file:///", "https://", "package://", "modulepath:/", "main.pkl"))
  }

  @Test
  fun `complete import`() {
    createPklFile("import \"<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label })
      .isEqualTo(listOf("pkl:", "file:///", "https://", "package://", "modulepath:/", "main.pkl"))
  }

  @Test
  fun `complete import expression`() {
    createPklFile("res = import(\"<caret>\")")
    val completions = getCompletions()
    assertThat(completions.map { it.label })
      .isEqualTo(listOf("pkl:", "file:///", "https://", "package://", "modulepath:/", "main.pkl"))
  }

  @Test
  fun `complete modulepath import`() {
    fakeProject.settingsManager.update {
      it.copy(modulepath = listOf(testProjectDir.resolve("lib")))
    }
    createPklFile("lib/target.pkl", "")
    createPklFile("import \"modulepath:/<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label }).isEqualTo(listOf("target.pkl"))
  }

  @Test
  fun `modulepath import completion prioritizes earlier entries`(
    @TempDir modulepath1: Path,
    @TempDir modulepath2: Path,
  ) {
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(modulepath1, modulepath2)) }
    val targetPath = createPklFile(modulepath1.resolve("target.pkl"), "")
    createPklFile(modulepath2.resolve("target.pkl"), "")
    createPklFile("import \"modulepath:/<caret>\"")
    val completions = getCompletions().map { (it.data as ModuleUriCompletionData).moduleUri }
    assert(completions.size == 1)
    assert(completions[0] == targetPath.toUri().toString())
  }

  @Test
  fun `complete modulepath archive import`(@TempDir tempDir: Path) {
    val libJar = tempDir.resolve("lib.jar")
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(libJar)) }
    createArchive(libJar, mapOf("file.pkl" to "", "dir/file2.pkl" to ""))
    createPklFile("import \"modulepath:/<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label }).isEqualTo(listOf("dir", "file.pkl"))
  }

  @Test
  fun `complete modulepath archive import in directory`(@TempDir tempDir: Path) {
    val libJar = tempDir.resolve("lib.jar")
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(libJar)) }
    createArchive(libJar, mapOf("dir/target.pkl" to ""))
    createPklFile("import \"modulepath:/dir/<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label }).isEqualTo(listOf("target.pkl"))
  }

  @Test
  fun `complete absolute paths within modulepath`(@TempDir tempDir: Path) {
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(tempDir)) }
    createPklFile(tempDir.resolve("bar.pkl").toString(), "bar = 1")
    createPklFile(tempDir.resolve("foo.pkl").toString(), "import \"/<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label }).hasSameElementsAs(listOf("bar.pkl", "foo.pkl"))
  }

  @Test
  fun `complete relative paths within modulepath`(
    @TempDir tempDir1: Path,
    @TempDir tempDir2: Path,
  ) {
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(tempDir1, tempDir2)) }
    createPklFile(tempDir1.resolve("bar/baz.pkl").toString(), "bar = 1")
    createPklFile(tempDir2.resolve("bar/foo.pkl").toString(), "import \"./<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label }).hasSameElementsAs(listOf("baz.pkl", "foo.pkl"))
  }
}
