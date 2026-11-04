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
package org.pkl.lsp.completion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.pkl.lsp.LspTestBase

class ModuleNameCompletionTest : LspTestBase() {

  fun configureProject() {
    createPklFile(
      "project/PklProject",
      """
      amends "pkl:Project"

      package {
        name = "foo.bar"
        baseUri = "package://example.com/foo.bar"
        version = "1.2.3"
        packageZipUrl = "https://example.com/foo.bar/\(version).zip"
      }
    """
        .trimIndent(),
    )
    fakeProject.pklProjectManager
      .syncProjects(
        // prevent other components from reacting to this change because these file gets deleted
        // after each test finishes
        emitEvents = false
      )
      .get()
  }

  @Test
  fun `test completion of module names from filename only, from scratch`() {
    createPklFile(
      "filename-only-scratch.pkl",
      """
          module <caret>
        """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].insertText).isEqualTo("`filename-only-scratch`")
  }

  @Test
  fun `test completion of open module names from filename only, from scratch`() {
    createPklFile(
      "filename-only-scratch.pkl",
      """
          open module <caret>
        """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].insertText).isEqualTo("`filename-only-scratch`")
  }

  @Test
  fun `test completion of module names from filename only, with prefix match`() {
    createPklFile(
      "filename-only-prefix.pkl",
      """
          module f<caret>
            """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].insertText).isEqualTo("`filename-only-prefix`")
  }

  @Test
  fun `test completion of module names from filename only, with no prefix match`() {
    createPklFile(
      "filename-only-noprefix.pkl",
      """
          module x<caret>
        """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).isEmpty()
  }

  @Test
  fun `test completion of module names from package, from scratch`() {
    configureProject()
    createPklFile(
      "project/baz/from-scratch.pkl",
      """
          module <caret>
            """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].insertText).isEqualTo("foo.bar.baz.`from-scratch`")
  }

  @Test
  fun `test completion of module names from package, with prefix match`() {
    configureProject()
    createPklFile(
      "project/baz/prefix.pkl",
      """
          module f<caret>
        """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].insertText).isEqualTo("foo.bar.baz.prefix")
  }

  @Test
  fun `test completion of module names from package, with no prefix match`() {
    configureProject()
    createPklFile(
      "project/baz/noprefix.pkl",
      """
          module x<caret>
            """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).isEmpty()
  }

  @Test
  fun `test completion of module names from package with partial project name and from scratch`() {
    configureProject()
    createPklFile(
      "project/baz/partial-project-name.pkl",
      """
          module foo.<caret>
        """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions.find { it.insertText == "bar.baz.`partial-project-name`" }).isNotNull
  }

  @Test
  fun `test completion of module names from package with partial project name and prefix match`() {
    configureProject()
    createPklFile(
      "project/baz/partial-project-name-prefix.pkl",
      """
          module foo.b<caret>
            """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].insertText).isEqualTo("bar.baz.`partial-project-name-prefix`")
  }

  @Test
  fun `test completion of module names from package with partial project name and no prefix match`() {
    configureProject()
    createPklFile(
      "project/baz/partial-project-name-noprefix.pkl",
      """
          module foo.x<caret>
        """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).isEmpty()
  }

  @Test
  fun `test completion of module names from package with partial file path and from scratch`() {
    configureProject()
    createPklFile(
      "project/baz/partial-filename.pkl",
      """
          module foo.bar.baz.<caret>
            """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].insertText).isEqualTo("`partial-filename`")
  }

  @Test
  fun `test completion of module names from package with partial file path and prefix match`() {
    configureProject()
    createPklFile(
      "project/baz/partial-filename-prefix.pkl",
      """
          module foo.bar.baz.p<caret>
        """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].insertText).isEqualTo("`partial-filename-prefix`")
  }

  @Test
  fun `test completion of module names from package with partial file path and no prefix match`() {
    configureProject()
    createPklFile(
      "project/baz/partial-filename-noprefix.pkl",
      """
          module foo.bar.baz.x<caret>
            """
        .trimIndent(),
    )
    val completions = getCompletions()
    assertThat(completions).isEmpty()
  }
}
