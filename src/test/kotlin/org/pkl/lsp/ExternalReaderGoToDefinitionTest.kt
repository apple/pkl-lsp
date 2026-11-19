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
package org.pkl.lsp

import java.nio.file.Path
import kotlin.io.path.exists
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.pkl.lsp.ast.PklClassProperty
import org.pkl.lsp.ast.lspUri
import org.pkl.lsp.services.ExternalReader

class ExternalReaderGoToDefinitionTest : LspTestBase() {
  companion object {
    private val javaExecutable: String =
      Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** Written by the `buildExternalReaderFixture` Gradle task. */
    private val fixtureArgfile: Path =
      Path.of(
          System.getProperty("externalReaderFixtureArgfile")
            ?: "build/externalReaderFixture/java.argfile"
        )
        .toAbsolutePath()

    /** The arguments to start the fixture reader with, serving [scheme]. */
    private fun fixtureArguments(scheme: String): List<String> = listOf("@$fixtureArgfile", scheme)
  }

  @BeforeEach
  override fun beforeEach() {
    check(fixtureArgfile.exists()) {
      "External reader fixture not found. Run `./gradlew buildExternalReaderFixture`."
    }
    super.beforeEach()
    // the project is shared between tests
    fakeProject.settingsManager.update { it.copy(externalModuleReaders = emptyMap()) }
  }

  // region helpers

  private fun configureSettingsReader(scheme: String = "fixture", servedScheme: String = scheme) {
    fakeProject.settingsManager.update {
      it.copy(
        externalModuleReaders =
          it.externalModuleReaders +
            (scheme to ExternalReader(javaExecutable, fixtureArguments(servedScheme)))
      )
    }
  }

  private fun configureProjectReader(scheme: String = "fixture") {
    createPklFile(
      "PklProject",
      """
      amends "pkl:Project"

      evaluatorSettings {
        externalModuleReaders {
          ["$scheme"] {
            executable = ${pklString(javaExecutable)}
            arguments { ${fixtureArguments(scheme).joinToString("; ") { pklString(it) }} }
          }
        }
      }
      """
        .trimIndent(),
    )
    fakeProject.pklProjectManager
      .syncProjects(
        // prevent other components from reacting to this change because these file gets deleted
        // after each test finishes
        emitEvents = false
      )
      .get()
  }

  private fun pklString(value: String) =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** The URI that the reader was asked for, of the module that [this] node is in. */
  private val org.pkl.lsp.ast.PklNode.readerUri: String
    get() = enclosingModule!!.virtualFile.uri.toString()

  // endregion

  // region readers from the settings

  @Test
  fun `settings - resolves import`() {
    configureSettingsReader()
    createPklFile("""import "fixture:/animals/d<caret>og.pkl"""")

    val resolved = goToDefinition().single()
    assertThat(resolved.enclosingModule!!.virtualFile).isInstanceOf(ExternalReaderFile::class.java)
    assertThat(resolved.enclosingModule!!.moduleName).isEqualTo("fixture.animals.dog")
    assertThat(resolved.readerUri).isEqualTo("fixture:/animals/dog.pkl")
  }

  @Test
  fun `settings - resolves member of imported module`() {
    configureSettingsReader()
    createPklFile(
      """
      import "fixture:/animals/dog.pkl"

      sound = dog.so<caret>und
      """
        .trimIndent()
    )

    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    assertThat(resolved.enclosingModule!!.moduleName).isEqualTo("fixture.animals.dog")
  }

  @Test
  fun `settings - resolves relative import from a module of the reader`() {
    configureSettingsReader()
    createPklFile("""import "fixture:/animals/wild/w<caret>olf.pkl"""")

    // wolf.pkl is `extends "../dog.pkl"`
    val wolf = goToDefinition().single().enclosingModule!!
    val dog = wolf.supermodule(wolf.virtualFile.pklProject)

    assertThat(dog).isNotNull
    assertThat(dog!!.virtualFile.uri.toString()).isEqualTo("fixture:/animals/dog.pkl")
  }

  @Test
  fun `settings - resolves triple-dot import from a module of the reader`() {
    configureSettingsReader()
    createPklFile("""import "fixture:/animals/wild/f<caret>ox.pkl"""")

    // fox.pkl is `extends ".../dog.pkl"`; dog.pkl is in the parent directory
    val fox = goToDefinition().single().enclosingModule!!
    val dog = fox.supermodule(fox.virtualFile.pklProject)

    assertThat(dog).isNotNull
    assertThat(dog!!.virtualFile.uri.toString()).isEqualTo("fixture:/animals/dog.pkl")
  }

  @Test
  fun `settings - resolves glob import to all matching modules`() {
    configureSettingsReader()
    createPklFile("""import* "fixture:/animals/*<caret>.pkl" as allAnimals""")

    val resolved = goToDefinition()

    assertThat(resolved.map { it.readerUri })
      .containsExactlyInAnyOrder("fixture:/animals/dog.pkl", "fixture:/animals/cat.pkl")
  }

  @Test
  fun `settings - module that the reader doesn't have stays unresolved`() {
    configureSettingsReader()
    createPklFile("""import "fixture:/animals/no<caret>pe.pkl"""")

    assertThat(goToDefinition()).isEmpty()
  }

  @Test
  fun `settings - reader for a scheme that it doesn't serve resolves nothing`() {
    // the process answers for `other`, not for `fixture`
    configureSettingsReader(scheme = "fixture", servedScheme = "other")
    createPklFile("""import "fixture:/animals/d<caret>og.pkl"""")

    assertThat(goToDefinition()).isEmpty()
  }

  @Test
  fun `settings - lsp uri of a module resolves to the same file`() {
    configureSettingsReader()
    createPklFile("""import "fixture:/animals/d<caret>og.pkl"""")

    val file = goToDefinition().single().enclosingModule!!.virtualFile

    assertThat(fakeProject.virtualFileManager.get(file.lspUri)).isSameAs(file)
  }

  // endregion

  // region readers from a PklProject

  @Test
  fun `project - resolves import`() {
    configureProjectReader()
    createPklFile("""import "fixture:/animals/d<caret>og.pkl"""")

    val resolved = goToDefinition().single()
    assertThat(resolved.enclosingModule!!.moduleName).isEqualTo("fixture.animals.dog")
    assertThat(resolved.readerUri).isEqualTo("fixture:/animals/dog.pkl")
  }

  @Test
  fun `project - resolves relative import from a module of the reader`() {
    configureProjectReader()
    createPklFile("""import "fixture:/animals/wild/w<caret>olf.pkl"""")

    // The reader is only declared by the project, so this needs the project that wolf.pkl was read
    // through.
    val wolf = goToDefinition().single().enclosingModule!!
    val dog = wolf.supermodule(wolf.virtualFile.pklProject)

    assertThat(dog).isNotNull
    assertThat(dog!!.virtualFile.uri.toString()).isEqualTo("fixture:/animals/dog.pkl")
  }

  @Test
  fun `project - resolves glob import to all matching modules`() {
    configureProjectReader()
    createPklFile("""import* "fixture:/animals/*<caret>.pkl" as allAnimals""")

    val resolved = goToDefinition()

    assertThat(resolved.map { it.readerUri })
      .containsExactlyInAnyOrder("fixture:/animals/dog.pkl", "fixture:/animals/cat.pkl")
  }

  @Test
  fun `project - lsp uri of a module resolves to the same file`() {
    configureProjectReader()
    createPklFile("""import "fixture:/animals/d<caret>og.pkl"""")

    val file = goToDefinition().single().enclosingModule!!.virtualFile

    assertThat(file.lspUri.query).startsWith("project=")
    assertThat(fakeProject.virtualFileManager.get(file.lspUri)).isSameAs(file)
  }

  @Test
  fun `project - reader takes precedence over the settings`() {
    // The settings' reader doesn't serve `fixture`, so resolving only works through the project's.
    configureSettingsReader(scheme = "fixture", servedScheme = "other")
    configureProjectReader()
    createPklFile("""import "fixture:/animals/d<caret>og.pkl"""")

    assertThat(goToDefinition().single().readerUri).isEqualTo("fixture:/animals/dog.pkl")
  }

  // endregion
}
