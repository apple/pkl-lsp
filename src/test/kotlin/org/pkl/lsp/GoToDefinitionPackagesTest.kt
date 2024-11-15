/*
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.pkl.lsp.ast.PklClassProperty
import org.pkl.lsp.ast.PklModuleClause
import org.pkl.lsp.packages.dto.PackageUri

class GoToDefinitionPackagesTest : LspTestBase() {
  companion object {
    @BeforeAll
    @JvmStatic
    fun beforeAll() {
      LspTestBase.beforeAll()
      fakeProject.pklCli
        .downloadPackage(
          listOf(
            PackageUri.create(
              "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.2"
            )!!
          ),
          pklCacheDir,
          false,
        )
        .get()
    }
  }

  @Test
  fun `resolve package asset uri`() {
    createPklFile(
      """
    amends "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.2#/AppEnvCluster.pkl<caret>"
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklModuleClause::class.java)
    resolved as PklModuleClause
    assertThat(resolved.enclosingModule?.virtualFile).isInstanceOf(JarFile::class.java)
  }

  @Test
  fun `resolve package dependency`() {
    createPklFile(
      """
    amends "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.2#/AppEnvCluster.pkl"
    
    deployments {
      ["foo"] {
        spec<caret> {}
      }
    }
    """
        .trimIndent()
    )
    val resolved = goToDefinition().single()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    assertThat(resolved.enclosingModule!!.moduleName).isEqualTo("k8s.api.apps.v1.Deployment")
  }

  @Test
  fun `resolve glob imports`() {
    createPklFile(
      """
      import* "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.2#/*.pkl<caret>"
    """
        .trimIndent()
    )

    val resolved = goToDefinition()
    assertThat(resolved).hasSize(1)
  }
}
