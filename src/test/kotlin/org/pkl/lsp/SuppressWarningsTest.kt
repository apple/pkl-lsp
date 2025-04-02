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

class SuppressWarningsTest : LspTestBase() {
  @Test
  fun `add suppression`() {
    val file =
      createPklVirtualFile(
        """
     const bar: Int?

     class Bar {
       foo: Int = bar
     }
    """
          .trimIndent()
      )
    val diagnostic = getSingleDiagnostic(file)
    assertThat(diagnostic.actions).hasSize(2)
    assertThat(diagnostic.actions[0].title).isEqualTo("Suppress 'TypeMismatch' for property 'foo'")
    assertThat(diagnostic.actions[1].title).isEqualTo("Suppress 'TypeMismatch' for class 'Bar'")
    runAction(diagnostic.actions[0].toMessage(diagnostic))
    assertThat(file.contents)
      .isEqualTo(
        """
     const bar: Int?

     class Bar {
       // noinspection TypeMismatch
       foo: Int = bar
     }
    """
          .trimIndent()
      )
  }

  @Test
  fun `add suppression to class`() {
    val file =
      createPklVirtualFile(
        """
     const bar: Int?

     class Bar {
       foo: Int = bar
     }
    """
          .trimIndent()
      )
    val diagnostic = getSingleDiagnostic(file)
    assertThat(diagnostic.actions[1].title).isEqualTo("Suppress 'TypeMismatch' for class 'Bar'")
    runAction(diagnostic.actions[1].toMessage(diagnostic))
    assertThat(file.contents)
      .isEqualTo(
        """
      const bar: Int?
  
      // noinspection TypeMismatch
      class Bar {
        foo: Int = bar
      }
    """
          .trimIndent()
      )
  }

  @Test
  fun `add suppression to already existing line comment`() {
    val file =
      createPklVirtualFile(
        """
     const bar: Int?

     class Bar {
       // noinspection FooBar
       foo: Int = bar
     }
    """
          .trimIndent()
      )
    val diagnostic = getSingleDiagnostic(file)
    runAction(diagnostic.actions[0].toMessage(diagnostic))
    assertThat(file.contents)
      .isEqualTo(
        """
     const bar: Int?

     class Bar {
       // noinspection FooBar,TypeMismatch
       foo: Int = bar
     }
    """
          .trimIndent()
      )
  }
}
