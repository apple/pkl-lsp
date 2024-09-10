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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.pkl.lsp.services.PklWorkspaceState

class SyncProjectsTest : LSPTestBase() {

  private lateinit var projectFile: Path

  @BeforeEach
  override fun beforeEach() {
    super.beforeEach()
    projectFile =
      createPklFile(
        "PklProject",
        """
      amends "pkl:Project"
      
      dependencies {
        ["appEnvCluster"] {
          uri = "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.2"
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

  @Test
  fun `resolves dependencies`() {
    val vfile = fakeProject.virtualFileManager.get(projectFile)
    assertThat(vfile).isNotNull
    vfile!!
    val pklProject = fakeProject.pklProjectManager.getPklProject(vfile)
    assertThat(pklProject).isNotNull
    pklProject!!
    assertThat(pklProject.projectDeps!!.resolvedDependencies)
      .containsOnlyKeys(
        "package://pkg.pkl-lang.org/pkl-k8s/k8s@1",
        "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1",
      )
    assertThat(pklProject.myDependencies).containsOnlyKeys("appEnvCluster")
  }

  @Test
  fun `syncing projects persists state`() {
    val pklLspDir = testProjectDir.resolve(".pkl-lsp")
    assertThat(pklLspDir).isDirectory()
    val projectsJsonFile = pklLspDir.resolve("projects.json")
    assertThat(projectsJsonFile).exists()
    val state = PklWorkspaceState.load(pklLspDir.resolve("projects.json"))
    assertThat(state.pklProjects).hasSize(1)
    val project = state.pklProjects.first()
    assertThat(project.projectFile).isEqualTo("PklProject")
    assertThat(project.resolvedDependencies)
      .containsOnlyKeys(
        "package://pkg.pkl-lang.org/pkl-k8s/k8s@1",
        "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1",
      )
    assertThat(project.declaredDependencies).containsOnlyKeys("appEnvCluster")
  }

  @Test
  @Disabled("For some reason this test only works in isolation")
  fun `changing a PklProject file causes a sync project notification message to appear`() {
    typeText("\n\n")
    saveFile()
    assertThat(TestLanguageClient.actionableNotifications).hasSize(1)
    assertThat(TestLanguageClient.actionableNotifications.first().commands).hasSize(1)
    assertThat(TestLanguageClient.actionableNotifications.first().commands.first().command)
      .isEqualTo("pkl.syncProjects")
  }

  @Test
  fun `resolve remote dependency`() {
    createPklFile(
      """
      import "@appEnvCluster/AppEnvCluster.pkl<caret>"
    """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
    val resolvedFile = resolved.first().containingFile
    assertThat(resolvedFile).isInstanceOf(JarFile::class.java)
  }

  @Test
  fun `resolve glob import`() {
    createPklFile(
      """
      import* "@appEnvCluster/*.pkl<caret>"
    """
        .trimIndent()
    )
    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
    val resolvedFile = resolved.first().containingFile
    assertThat(resolvedFile).isInstanceOf(JarFile::class.java)
  }
}
