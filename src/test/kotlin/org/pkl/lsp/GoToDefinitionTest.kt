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

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
  fun `resolve modulepath import`(@TempDir tempDir: Path) {
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(tempDir)) }
    val file = tempDir.resolve("target.pkl")
    createPklFile(file, "")
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
    assertThat(Path.of(resolvedFile.uri).absolutePathString()).isEqualTo(file.absolutePathString())
  }

  @Test
  fun `modulepath import resolving prioritizes earlier entries`(@TempDir tempDir: Path) {
    val modulePath1 = tempDir.resolve("lib")
    val modulePath2 = tempDir.resolve("lib2")
    val module1 = modulePath1.resolve("target.pkl")
    val module2 = modulePath2.resolve("target.pkl")

    fakeProject.settingsManager.update { it.copy(modulepath = listOf(modulePath1, modulePath2)) }
    createPklFile(module1, "")
    createPklFile(module2, "")
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
    assertThat(Path.of(resolvedFile.uri).absolutePathString())
      .isEqualTo(module1.absolutePathString())
  }

  @Test
  fun `resolve field in modulepath`(@TempDir tempDir: Path) {
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(tempDir)) }
    val targetModulePath = tempDir.resolve("Target.pkl")

    createPklFile(
      targetModulePath,
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
    assertThat(Path.of(resolvedFile.uri).absolutePathString())
      .isEqualTo(targetModulePath.absolutePathString())
  }

  @Test
  fun `resolve relative field in modulepath`(@TempDir tempDir: Path) {
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(tempDir)) }
    val targetModulePath = tempDir.resolve("Target.pkl")
    createPklFile(
      targetModulePath,
      """
      module Target

      field: String
    """
        .trimIndent(),
    )
    createPklFile(
      tempDir.resolve("Stopover.pkl"),
      """
      module Stopover

      import "Target.pkl"

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
    assertThat(resolvedFile.uri.path).endsWith("Target.pkl")
  }

  @Test
  fun `resolve modulepath field in modulepath`(@TempDir tempDir: Path) {

    fakeProject.settingsManager.update { it.copy(modulepath = listOf(tempDir)) }
    val modulepath1 = tempDir.resolve("nested")
    val modulepath2 = tempDir.resolve("lib")

    fakeProject.settingsManager.update { it.copy(modulepath = listOf(modulepath1, modulepath2)) }
    val target = modulepath1.resolve("Target.pkl")

    createPklFile(
      target,
      """
      module Target

      field: String
    """
        .trimIndent(),
    )
    createPklFile(
      modulepath2.resolve("Stopover.pkl"),
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
    assertThat(Path.of(resolvedFile.uri).absolutePathString())
      .isEqualTo(target.absolutePathString())
  }

  @Test
  fun `resolve modulepath archive import inside archive`(@TempDir tempDir: Path) {
    fakeProject.settingsManager.update { it.copy(modulepath = listOf(tempDir.resolve("lib.jar"))) }
    createArchive(tempDir.resolve("lib.jar"), mapOf("dir/target.pkl" to ""))
    createPklFile(
      """
      import "modulepath:/dir/target<caret>.pkl"
    """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
    assertThat(resolved[0]).isInstanceOf(PklModule::class.java)
    val resolvedFile = resolved.first().containingFile
    assertThat(resolvedFile.uri.schemeSpecificPart).endsWith("/lib.jar!/dir/target.pkl")
  }

  @Test
  fun `relative resolution across modulepath elements prefers earlier directory`(
    @TempDir tempDir: Path
  ) {
    fakeProject.settingsManager.update {
      it.copy(
        modulepath =
          listOf(tempDir.resolve("x/y"), tempDir.resolve("x/z"), tempDir.resolve("x/z.zip"))
      )
    }
    createArchive(tempDir.resolve("x/z.zip"), mapOf("Bar.pkl" to "baz = 1"))
    createPklFile(tempDir.resolve("x/z/Bar.pkl"), "baz = 0")
    createPklFile(
      tempDir.resolve("x/y/Foo.pkl"),
      """
      import "Bar.pkl"

      bar: Bar
    """
        .trimIndent(),
    )
    createPklFile(
      tempDir.resolve("x/test.pkl"),
      """
      import "modulepath:/Foo.pkl"

      foo = new Foo {
        bar {
          baz<caret> = 2
        }
      }
    """
        .trimIndent(),
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    val uri = resolved.enclosingModule!!.uri
    assertThat(uri.normalize().path.endsWith("/x/z/Bar.pkl"))
  }

  @Test
  fun `relative resolution across modulepath elements prefers earlier archive`(
    @TempDir tempDir: Path
  ) {
    fakeProject.settingsManager.update {
      it.copy(
        modulepath =
          listOf(tempDir.resolve("x/y"), tempDir.resolve("x/z.zip"), tempDir.resolve("x/z"))
      )
    }
    createArchive(tempDir.resolve("x/z.zip"), mapOf("Bar.pkl" to "baz = 1"))
    createPklFile(tempDir.resolve("x/z/Bar.pkl"), "baz = 0")
    createPklFile(
      tempDir.resolve("x/y/Foo.pkl"),
      """
      import "./Bar.pkl"

      bar: Bar
    """
        .trimIndent(),
    )
    createPklFile(
      "x/test.pkl",
      """
      import "modulepath:/Foo.pkl"

      foo = new Foo {
        bar {
          baz<caret> = 2
        }
      }
    """
        .trimIndent(),
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    val uri = resolved.enclosingModule!!.uri
    assertThat(uri.scheme).isEqualTo("jar")
    assertThat(uri.schemeSpecificPart).startsWith("file:")
    assertThat(uri.schemeSpecificPart).endsWith("/x/z.zip!/Bar.pkl")
  }

  @Test
  fun `relative import from non-modulepath source does not resolve into modulepath`() {
    fakeProject.settingsManager.update {
      it.copy(modulepath = listOf(testProjectDir.resolve("lib")))
    }
    createPklFile("lib/target.pkl", "value = 1")
    createPklFile(
      """
      import "./target<caret>.pkl"
      """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).isEmpty()
  }

  @Test
  fun `relative import from modulepath source does not escape to filesystem`() {
    fakeProject.settingsManager.update {
      it.copy(modulepath = listOf(testProjectDir.resolve("lib")))
    }
    createPklFile("External.pkl", "value = 0")
    createPklFile(
      "lib/Foo.pkl",
      """
      import "../External.pkl"

      external: External
      """
        .trimIndent(),
    )
    createPklFile(
      """
      import "modulepath:/Foo.pkl"

      foo = new Foo {
        external {
          value<caret> = 1
        }
      }
      """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).isEmpty()
  }

  @Test
  fun `resolve backtick names -- module keyword`() {
    val modulePklFile = createPklFile("module.pkl", "")
    createPklFile(
      """
      import "module.pkl"
      
      res = `module`<caret>
      """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved[0]).isInstanceOf(PklModule::class.java)
    val resolvedFile = resolved.first().containingFile
    assertThat(Path.of(resolvedFile.uri).absolutePathString())
      .isEqualTo(modulePklFile.absolutePathString())
  }

  @Test
  fun `resolve backtick names -- quoted identifiers are same as unquoted`() {
    createPklFile(
      """
      foo = 1
      
      res = `foo`<caret>
    """
        .trimIndent()
    )

    val resolved = goToDefinition()
    assertThat(resolved.first()).isInstanceOf(PklClassProperty::class.java)
  }

  @Test
  fun `resolve backtick names -- quoted identifiers are same as unquoted 2`() {
    createPklFile(
      """
      `foo` = 1
      
      res = foo<caret>
    """
        .trimIndent()
    )

    val resolved = goToDefinition()
    assertThat(resolved.first()).isInstanceOf(PklClassProperty::class.java)
  }

  @Test
  fun `resolve reference property`() {
    createPklFile(
      """
      import "pkl:ref"

      class Domain extends ref.Domain

      class Bird {
        name: String
      }

      r: ref.Reference<Domain, Bird>

      res = r.na<caret>me
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.name).isEqualTo("name")
    assertThat(resolved.parentOfTypes(PklClass::class)!!.name).isEqualTo("Bird")
  }
}
