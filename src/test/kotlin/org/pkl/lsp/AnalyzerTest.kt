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
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import org.pkl.lsp.ast.PklModule

class AnalyzerTest : LspTestBase() {
  @Test
  fun `const in object members is not supported before 0_27`() {
    val code =
      """
      @ModuleInfo { minPklVersion = "0.26.0" }
      module mytest

      foo = new Dynamic {
          const bar = 1
      }
      """
        .trimIndent()

    val module = parse(code)
    val diagnostics = fakeProject.diagnosticsManager.getDiagnostics(module)
    assertThat(diagnostics).hasSize(1)
    val diagnostic = diagnostics[0]
    assertThat(diagnostic.severity).isEqualTo(DiagnosticSeverity.Error)
    assertThat(diagnostic.message)
      .isEqualTo(
        """
      Modifier 'const' cannot be applied to object members in this Pkl version.
      Required Pkl version: `0.27.0`. Detected Pkl version: `0.26.0`
      """
          .trimIndent()
      )
  }

  @Test
  fun `const without local is not supported in object members`() {
    val code =
      """
      module mytest

      foo = new Dynamic {
          const bar = 1
      }
      """
        .trimIndent()

    val module = parse(code)
    val diagnostics = fakeProject.diagnosticsManager.getDiagnostics(module)
    assertThat(diagnostics).hasSize(1)
    val diagnostic = diagnostics[0]
    assertThat(diagnostic.severity).isEqualTo(DiagnosticSeverity.Error)
    assertThat(diagnostic.message)
      .isEqualTo("Modifier 'const' can only be applied to object members who are also 'local'")
  }

  private fun parse(code: String): PklModule {
    val file = EphemeralFile(code, fakeProject)
    return file.getModule().get()!!
  }
}
