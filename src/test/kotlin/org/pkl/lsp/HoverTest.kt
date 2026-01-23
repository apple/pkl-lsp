/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.junit.jupiter.api.Disabled
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

  @Test
  fun `compute type for recursive method`() {
    createPklFile(
      """
        function fi<caret>b(num: UInt) =
          if (num == 0) 0
          else if (num <= 2) 1
          else fib(num - 1) + fib(num - 2)
      """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("function fib(num: UInt): unknown")
  }

  @Test
  fun `compute type that depends on recursive method`() {
    createPklFile(
      """
        function fib(num: UInt) =
          if (num == 0) 0
          else if (num <= 2) 1
          else fib(num - 1) + fib(num - 2)
          
        local res<caret> = fib(5)
      """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("local res: unknown")
  }

  @Test
  fun `compute type for Bytes - for generator index`() {
    createPklFile(
      """
        foo {
          for (idx<caret>, byte in Bytes(1, 2, 3)) {
            byte
          }
        }
        """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("idx: Int")
  }

  @Test
  fun `compute type for Bytes - for generator value`() {
    createPklFile(
      """
        foo {
          for (idx, byte<caret> in Bytes(1, 2, 3)) {
            byte
          }
        }
        """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("byte: UInt8")
  }

  @Test
  fun `compute type for Bytes - addition`() {
    createPklFile(
      """
        local foo<caret> = Bytes(1, 2) + Bytes(3, 4)
        """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("foo: Bytes")
  }

  @Test
  fun `compute type for Bytes - subscript`() {
    createPklFile(
      """
        local bytes = Bytes(1, 2, 3)
        local foo<caret> = bytes[0]
        """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("foo: UInt8")
  }

  @Test
  fun `BaseValueRenderer convertPropertyTransformers entry value object body this resolution`() {
    createPklFile(
      """
        /// this is foo
        class Foo extends ConvertProperty { 
          /// this is bar
          bar: String = "bar"
          render = (_, _) -> Pair(bar, bar) 
        }
        
        output {
          renderer {
            convertPropertyTransformers {
              [Foo] { b<caret>ar = "baz" }
            }
          }
        }
        """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("this is bar")
  }

  @Test
  fun `BaseValueRenderer convertPropertyTransformers entry value assigned lambda parameter resolution`() {
    createPklFile(
      """
        /// this is foo
        class Foo extends ConvertProperty { 
          /// this is bar
          bar: String = "bar"
          render = (_, _) -> Pair(bar, bar) 
        }
        
        output {
          renderer {
            convertPropertyTransformers {
              [Foo] = (it) -> (i<caret>t) { bar = "qux" }
            }
          }
        }
        """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("it: Foo")
  }

  @Test
  @Disabled
  fun `BaseValueRenderer convertPropertyTransformers entry value assigned lambda parameter property resolution`() {
    createPklFile(
      """
        /// this is foo
        class Foo extends ConvertProperty { 
          /// this is bar
          bar: String = "bar"
          render = (_, _) -> Pair(bar, bar) 
        }
        
        output {
          renderer {
            convertPropertyTransformers {
              [Foo] = (it) -> (it) { b<caret>ar = "quux" }
            }
          }
        }
        """
        .trimIndent()
    )
    val hoverText = getHoverText()
    assertThat(hoverText).contains("this is bar")
  }
}
