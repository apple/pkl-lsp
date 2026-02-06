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
import org.junit.jupiter.api.Test
import org.pkl.lsp.ast.*

class GoToDefinitionTest : LspTestBase() {
  @Test
  fun `resolve property name in scope`() {
    createPklFile(
      """
      prop = 42

      myProp = prop<caret>
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklProperty::class.java)
    resolved as PklProperty
    assertThat(resolved.name).isEqualTo("prop")
  }

  @Test
  fun `resolve property name this`() {
    createPklFile(
      """
      class MyClass {
        name: String
      }

      value: MyClass = new {
        name<caret> = "Bob"
      }
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.name).isEqualTo("name")
    assertThat(resolved.parentOfTypes(PklClass::class)!!.name).isEqualTo("MyClass")
  }

  @Test
  fun `resolve function name`() {
    createPklFile(
      """
      function foo() = 42

      result = fo<caret>o()
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklMethodHeader::class.java)
    resolved as PklMethodHeader
    assertThat(resolved.identifier!!.text).isEqualTo("foo")
  }

  @Test
  fun `resolve function parameter`() {
    createPklFile(
      """
      function foo(myParam: String) =
        myParam<caret> + 5
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklTypedIdentifier::class.java)
    resolved as PklTypedIdentifier
    assertThat(resolved.identifier!!.text).isEqualTo("myParam")
    assertThat(resolved.typeAnnotation!!.type!!.render()).isEqualTo("String")
  }

  @Test
  fun `resolve class definition`() {
    createPklFile(
      """
        class Person

        person: Person<caret>
      """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklClass::class.java)
    resolved as PklClass
    assertThat(resolved.identifier!!.text).isEqualTo("Person")
  }

  @Test
  fun `resolve base module type`() {
    createPklFile(
      """
        prop: String<caret>
      """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklClass::class.java)
    assertThat(resolved.isInPklBaseModule)
    resolved as PklClass
    assertThat(resolved.identifier!!.text).isEqualTo("String")
  }

  @Test
  fun `resolve simple import`() {
    createPklFile(
      "lib.pkl",
      """
      foo = 1
    """
        .trimIndent(),
    )
    createPklFile(
      """
      import "lib.pkl"

      result = lib.foo<caret>
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklProperty::class.java)
    resolved as PklProperty
    assertThat(resolved.name).isEqualTo("foo")
    assertThat(resolved.enclosingModule!!.uri.path).endsWith("lib.pkl")
  }

  @Test
  fun `resolve import module uri`() {
    createPklFile("lib.pkl", "")
    createPklFile(
      """
      import "lib<caret>.pkl"
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
    resolved as PklModule
    assertThat(resolved.enclosingModule!!.uri.path).endsWith("lib.pkl")
  }

  @Test
  fun `resolve when generator predicate`() {
    createPklFile(
      """
      bar = "module level"

      foo {
        bar = "foo level"
        when (ba<caret>r == 5) { true }
      }
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklProperty::class.java)
    resolved as PklProperty
    assertThat(resolved.expr!!.text).isEqualTo("\"module level\"")
  }

  @Test
  fun `resolve glob import`() {
    createPklFile("foo.pkl", "")
    createPklFile("bar.pkl", "")
    createPklFile(
      """
      result = import*("*.pkl<caret>")
    """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).isNotEmpty
    assertThat(resolved).hasSize(3)
    assertThat(resolved.map { it.containingFile.name })
      .hasSameElementsAs(setOf("main.pkl", "foo.pkl", "bar.pkl"))
  }

  @Test
  fun `resolve local property type`() {
    createPklFile(
      """
        class Person {
            name: String
        }

        foo {
            ["bar"] {
                local r: Person = new {
                    name<caret> = "Bob"
                }
            }
        }
      """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
    assertThat(resolved[0]).isInstanceOf(PklClassProperty::class.java)
    assertThat((resolved[0] as PklClassProperty).name).isEqualTo("name")
  }

  @Test
  fun `resolve modulepath import`() {
    fakeProject.settingsManager.settings.modulepath = listOf(testProjectDir.resolve("lib"))
    createPklFile("lib/target.pkl", "")
    createPklFile(
      """
      import "modulepath:/target<caret>.pkl"
    """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
    assertThat(resolved[0]).isInstanceOf(PklModule::class.java)
    val resolvedFile = resolved.first().containingFile
    assertThat(resolvedFile.uri.path).endsWith("/lib/target.pkl")
  }

  @Test
  fun `resolve field in modulepath`() {
    fakeProject.settingsManager.settings.modulepath = listOf(testProjectDir.resolve("lib"))
    createPklFile(
      "lib/Target.pkl",
      """
      module Target

      field: String
    """
        .trimIndent(),
    )
    createPklFile(
      """
      import "modulepath:/Target.pkl"

      target: Target = new {
        field<caret> = "field"
      }
    """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
    assertThat(resolved[0]).isInstanceOf(PklClassProperty::class.java)
    assertThat((resolved[0] as PklClassProperty).name).isEqualTo("field")
    val resolvedFile = resolved.first().containingFile
    assertThat(resolvedFile.uri.path).endsWith("/lib/Target.pkl")
  }

  @Test
  fun `resolve relative field in modulepath`() {
    fakeProject.settingsManager.settings.modulepath = listOf(testProjectDir.resolve("lib"))
    createPklFile(
      "lib/Target.pkl",
      """
      module Target

      field: String
    """
        .trimIndent(),
    )
    createPklFile(
      "lib/Stopover.pkl",
      """
      module Stopover

      import "./Target.pkl"

      target: Target
    """
        .trimIndent(),
    )
    createPklFile(
      """
      import "modulepath:/Stopover.pkl"

      stopover: Stopover = new {
        target {
          field<caret> = "field"
        }
      }
    """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
    assertThat(resolved[0]).isInstanceOf(PklClassProperty::class.java)
    assertThat((resolved[0] as PklClassProperty).name).isEqualTo("field")
    val resolvedFile = resolved.first().containingFile
    assertThat(resolvedFile.uri.path).endsWith("/lib/Target.pkl")
  }

  @Test
  fun `resolve modulepath field in modulepath`() {
    fakeProject.settingsManager.settings.modulepath =
      listOf(testProjectDir.resolve("nested"), testProjectDir.resolve("lib"))
    createPklFile(
      "nested/Target.pkl",
      """
      module Target

      field: String
    """
        .trimIndent(),
    )
    createPklFile(
      "lib/Stopover.pkl",
      """
      module Stopover

      import "modulepath:/Target.pkl"

      target: Target
    """
        .trimIndent(),
    )
    createPklFile(
      """
      import "modulepath:/Stopover.pkl"

      stopover: Stopover = new {
        target {
          field<caret> = "field"
        }
      }
    """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
    assertThat(resolved[0]).isInstanceOf(PklClassProperty::class.java)
    assertThat((resolved[0] as PklClassProperty).name).isEqualTo("field")
    val resolvedFile = resolved.first().containingFile
    assertThat(resolvedFile.uri.path).endsWith("/nested/Target.pkl")
  }
}
