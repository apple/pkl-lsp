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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompletionFeatureTest : LspTestBase() {
  @Test
  fun `complete amends header`() {
    createPklFile("amends \"<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label })
      .isEqualTo(listOf("pkl:", "file:///", "https://", "package://", "main.pkl"))
  }

  @Test
  fun `complete import`() {
    createPklFile("import \"<caret>\"")
    val completions = getCompletions()
    assertThat(completions.map { it.label })
      .isEqualTo(listOf("pkl:", "file:///", "https://", "package://", "main.pkl"))
  }

  @Test
  fun `complete import expression`() {
    createPklFile("res = import(\"<caret>\")")
    val completions = getCompletions()
    assertThat(completions.map { it.label })
      .isEqualTo(listOf("pkl:", "file:///", "https://", "package://", "main.pkl"))
  }
}
