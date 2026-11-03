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
package org.pkl.lsp.actions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.pkl.lsp.LspTestBase
import org.pkl.lsp.analyzers.PklProblemGroups

class PklParenthesizeComparisonQuickfixTest : LspTestBase() {
  private fun applyFix(source: String, expected: String) {
    val file = createPklVirtualFile(source)
    val diagnostic =
      getDiagnostics(file).find { it.problemGroup == PklProblemGroups.unresolvedElement }
    assertThat(diagnostic).isNotNull
    val action = diagnostic!!.actions.find { it.title == "Wrap left-hand side with parentheses" }
    runAction(action!!.toMessage(diagnostic))
    assertThat(file.contents).isEqualTo(expected)
  }

  @Test
  fun `wraps unqualified property access`() {
    applyFix(
      """
      foo = 1
      bar = 2
      baz = 3
      res = fo<caret>o < bar > (baz)
      """
        .trimIndent(),
      """
      foo = 1
      bar = 2
      baz = 3
      res = (foo) < bar > (baz)
      """
        .trimIndent(),
    )
  }

  @Test
  fun `wraps qualified property access including receiver`() {
    applyFix(
      """
      foo = 1
      bar = 2
      baz = 3
      res = module.fo<caret>o < bar > (baz)
      """
        .trimIndent(),
      """
      foo = 1
      bar = 2
      baz = 3
      res = (module.foo) < bar > (baz)
      """
        .trimIndent(),
    )
  }

  @Test
  fun `wraps super property access including super keyword`() {
    applyFix(
      """
      open class A { x = 1 }
      bar = 2
      baz = 3
      class B extends A { y = super.<caret>x < bar > (baz) }
      """
        .trimIndent(),
      """
      open class A { x = 1 }
      bar = 2
      baz = 3
      class B extends A { y = (super.x) < bar > (baz) }
      """
        .trimIndent(),
    )
  }

  @Test
  fun `no fix offered when member does not exist`() {
    val file = createPklVirtualFile("res = no<caret>pe<String>(1)")
    val actions = getAllCodeActions(file)
    assertThat(actions).isEmpty()
  }
}
