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
package org.pkl.lsp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HoverTest : LspTestBase() {
  @Test
  fun `hover produces docs`() {
    createPklFile(
      """
      /// This is the docs for foo!
      class Fo<caret>o {
      }
    """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText)
      .contains(
        """
      ```pkl
      class Foo
      ```
    """
          .trimIndent()
      )
    assertThat(hoverText).contains("This is the docs for foo!")
  }

  @Test
  fun `member links get rendered`() {
    createPklFile(
      """
      import "main.pkl"

      baz: String
      
      class Foo {
        /// [bar] [baz] [custom *link* text][Foo] [main] [main.Foo] [doFoo()] [main.doFoo()]
        b<caret>ar: String
      }
      
      function doFoo(): String = "foo"
    """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("[`bar`](${testProjectDir.resolve("main.pkl").toUri()}")
    assertThat(hoverText).contains("[`baz`](${testProjectDir.resolve("main.pkl").toUri()}")
    assertThat(hoverText)
      .contains("[`custom *link* text`](${testProjectDir.resolve("main.pkl").toUri()}")
    assertThat(hoverText).contains("[`main`](${testProjectDir.resolve("main.pkl").toUri()}")
    assertThat(hoverText).contains("[`main.Foo`](${testProjectDir.resolve("main.pkl").toUri()}")
    assertThat(hoverText).contains("[`doFoo()`](${testProjectDir.resolve("main.pkl").toUri()}")
    assertThat(hoverText).contains("[`main.doFoo()`](${testProjectDir.resolve("main.pkl").toUri()}")
  }

  @Test
  fun `member link keywords turn into code`() {
    createPklFile(
      """
      /// [true] [false] [null]
      myPro<caret>p: Boolean
    """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("`true`")
    assertThat(hoverText).contains("`false`")
    assertThat(hoverText).contains("`null`")
  }
}
