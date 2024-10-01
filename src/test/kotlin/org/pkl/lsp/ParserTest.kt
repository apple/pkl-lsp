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

import java.nio.file.Path
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.pkl.lsp.ast.*

class ParserTest {

  private val project = Project(PklLSPServer(true))
  private val parser = project.pklParser

  @Test
  fun `parse types`() {
    val code =
      """
        tunknown: unknown
        
        tnothing: nothing
        
        tmodule: module
        
        tstr: "foo"
        
        tqual: Mapping<Int, String>
        
        tpar: (String)
        
        tnull: Int?
        
        tconst: String(!isEmpty, !isBlank)
        
        tunion: Int|*String
        
        tfun: (Int, Int) -> String
        """
        .trimIndent()

    val module = parse(code)
    val rawProps = module.children

    rawProps.forEach { assertThat(it).isInstanceOf(PklClassProperty::class.java) }
    val props = rawProps.filterIsInstance<PklClassProperty>()

    assertThat(props[0].type).isInstanceOf(PklUnknownType::class.java)

    assertThat(props[1].type).isInstanceOf(PklNothingType::class.java)

    assertThat(props[2].type).isInstanceOf(PklModuleType::class.java)

    val type3 = props[3].type
    assertThat(type3).isInstanceOf(PklStringLiteralType::class.java)
    assertThat((type3 as PklStringLiteralType).stringConstant.value).isEqualTo("\"foo\"")

    val type4 = props[4].type
    assertThat(type4).isInstanceOf(PklDeclaredType::class.java)
    val decl = type4 as PklDeclaredType
    assertThat(decl.name.text).isEqualTo("Mapping")
    assertThat(decl.typeArgumentList).isNotNull
    assertThat(decl.typeArgumentList!!.types.size).isEqualTo(2)

    val type5 = props[5].type
    assertThat(type5).isInstanceOf(PklParenthesizedType::class.java)
    assertThat((type5 as PklParenthesizedType).type.text).isEqualTo("String")

    val type6 = props[6].type
    assertThat(type6).isInstanceOf(PklNullableType::class.java)
    assertThat((type6 as PklNullableType).type.text).isEqualTo("Int")

    val type7 = props[7].type
    assertThat(type7).isInstanceOf(PklConstrainedType::class.java)
    val cons = type7 as PklConstrainedType
    assertThat(cons.type?.text).isEqualTo("String")
    assertThat(cons.exprs.size).isEqualTo(2)

    val type8 = props[8].type
    assertThat(type8).isInstanceOf(PklUnionType::class.java)
    val union = type8 as PklUnionType
    assertThat(union.leftType).isInstanceOf(PklDeclaredType::class.java)
    assertThat(union.leftType.text).isEqualTo("Int")
    assertThat(union.rightType).isInstanceOf(PklDefaultUnionType::class.java)
    assertThat((union.rightType as PklDefaultUnionType).type.text).isEqualTo("String")

    val type9 = props[9].type
    assertThat(type9).isInstanceOf(PklFunctionType::class.java)
    val func = type9 as PklFunctionType
    assertThat(func.parameterList.size).isEqualTo(2)
    assertThat(func.parameterList[0].text).isEqualTo("Int")
    assertThat(func.parameterList[1].text).isEqualTo("Int")
    assertThat(func.returnType.text).isEqualTo("String")
  }

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
    val prop = mod.children[0]
    assertThat(prop.text).isEqualTo("const `fo©o` = 1")
    val prop2 = mod.children[1]
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
  fun `Nested comments inside binary expression are parsed correctly`() {
    val code =
      """
      something = one
        || two
        // comment here
        || three
        /* block comment here */
        || four
    """
        .trimIndent()

    val mod = parse(code)
    val prop = mod.children[0]
    assertThat(prop)
        .isInstanceOf(PklClassProperty::class.java)
        .hasFieldOrPropertyWithValue("type", null)
  }

  @Test
  fun `Nested errors inside binary expression are parsed correctly`() {
    val code =
      """
      something = one
        || bar.]
        || three
    """
        .trimIndent()

    val mod = parse(code)
    val prop = mod.children[0]
    assertThat(prop)
        .isInstanceOf(PklClassProperty::class.java)
        .hasFieldOrPropertyWithValue("type", null)
  }

  private fun parse(text: String): PklModule {
    val node = parser.parse(text)
    return PklModuleImpl(node, FsFile(Path.of("."), project))
  }
}
