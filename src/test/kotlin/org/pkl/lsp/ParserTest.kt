/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.pkl.lsp.ast.*

class ParserTest {
  private val project = Project(PklLspServer(true))
  private val parser = project.pklParser

  @Test
  fun `Node text works with UTF-8 chars`() {
    val code =
      """
      // a©
      const `fo©o` = 1
      
      bar = 3
      
    """
        .trimIndent()

    val mod = parse(code)
    val prop = mod.children[1]
    assertThat(prop.text).isEqualTo("const `fo©o` = 1")
    val prop2 = mod.children[2]
    assertThat(prop2.text).isEqualTo("bar = 3")
  }

  @Test
  fun `parse strings`() {
    val code =
      """
      foo = "my \n \u{32} string"
      
      bar = "my \(inter) string"
    """
        .trimIndent()

    val mod = parse(code)
    val strLiteral = mod.children[0].children[2]
    assertThat(strLiteral).isInstanceOf(PklSingleLineStringLiteral::class.java)
    strLiteral as PklSingleLineStringLiteral
    assertThat(strLiteral.escapedText()).isEqualTo("my \n 2 string")

    val strLiteral2 = mod.children[1].children[2]
    assertThat(strLiteral2).isInstanceOf(PklSingleLineStringLiteral::class.java)
    strLiteral2 as PklSingleLineStringLiteral
    assertThat(strLiteral2.escapedText()).isNull()
  }

  @Test
  fun `Node can be accessed from other threads`() {
    val code =
      """
      foo = 1
    """
        .trimIndent()

    val mod = parse(code)
    val exe = Executors.newSingleThreadExecutor()
    exe
      .submit {
        val prop = mod.children[0]
        assertThat(prop.text).isEqualTo("foo = 1")
      }
      .get()
  }

  @Test
  fun `Errors become nodes`() {
    val code =
      """
      foo = bar.]
    """
        .trimIndent()

    val mod = parse(code)
    val err = mod.children[1]
    assertThat(err).isInstanceOf(PklError::class.java)
    assertThat(err.text).isEqualTo(".]")
  }

  @Test
  fun `Doc comments are present in the tree`() {
    val code =
      """
      /// A doc
      /// comment
      foo = 1
    """
        .trimIndent()

    val mod = parse(code)
    val prop = mod.children[0]
    assertThat(prop).isInstanceOf(PklClassProperty::class.java)
    prop as PklClassProperty
    assertThat(prop.parsedComment).isEqualTo("A doc\ncomment")
  }

  @Test
  fun `Nested errors inside binary expression are parsed correctly`() {
    val code =
      """
      prop1 = one.]
        || two
    """
        .trimIndent()

    val mod = parse(code)
    val prop = mod.children[0]
    val expr = assertClassPropertyExpr(prop)
    assertThat(expr).isInstanceOf(PklLogicalOrExpr::class.java)
    expr as PklLogicalOrExpr
    assertThat(expr.leftExpr).isInstanceOf(PklUnqualifiedAccessExpr::class.java)
    assertThat(expr.rightExpr).isInstanceOf(PklUnqualifiedAccessExpr::class.java)
    assertThat(expr.operator.type).isEqualTo(TokenType.OR)
    val err = expr.children.find { it is PklError } as? PklError
    assertThat(err).isNotNull
    assertThat(err!!.text).isEqualTo(".]")
  }

  private fun assertClassPropertyExpr(node: PklNode): PklExpr {
    assertThat(node).isInstanceOf(PklClassProperty::class.java)
    val expr = (node as PklClassProperty).expr
    assertThat(expr).isNotNull
    return expr!!
  }

  private fun parse(text: String): PklModule {
    val tree = parser.parse(text)
    return PklModuleImpl(tree.rootNode, FsFile(Path.of("."), project))
  }
}
