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

class ModifierQuickfixTest : LspTestBase() {
  @Test
  fun `add modifier 'local' - no existing modifiers`() {
    val file =
      createPklVirtualFile(
        """
     amends "pkl:test"

     foo: String = "Hello"
    """
          .trimIndent()
      )
    val diagnostic = getSingleDiagnostic(file)
    assertThat(diagnostic.message).isEqualTo("Missing modifier 'local'")
    val action = diagnostic.actions.find { it.title == "Add modifier 'local'" }
    assertThat(action).isNotNull
    runAction(action!!.toMessage(diagnostic))
    assertThat(file.contents)
      .isEqualTo(
        """
     amends "pkl:test"

     local foo: String = "Hello"
    """
          .trimIndent()
      )
  }

  @Test
  fun `add modifier 'local' - existing modifiers`() {
    val file =
      createPklVirtualFile(
        """
     amends "pkl:test"

     abstract external foo: String = "Hello"
    """
          .trimIndent()
      )
    val diagnostic = getSingleDiagnostic(file)
    assertThat(diagnostic.message).isEqualTo("Missing modifier 'local'")
    val action = diagnostic.actions.find { it.title == "Add modifier 'local'" }
    assertThat(action).isNotNull
    runAction(action!!.toMessage(diagnostic))
    // modifier gets inserted in between 'abstract' and 'external'
    assertThat(file.contents)
      .isEqualTo(
        """
     amends "pkl:test"

     abstract local external foo: String = "Hello"
    """
          .trimIndent()
      )
  }
}
