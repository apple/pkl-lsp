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
package org.pkl.lsp.analyzers

import org.eclipse.lsp4j.DiagnosticSeverity
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.actions.PklCodeAction
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.Span
import org.pkl.lsp.ast.isInStdlib

/**
 * Scans the source tree, and builds [PklDiagnostic]s.
 *
 * Diagnostics then get reported back to the user.
 */
abstract class Analyzer(project: Project) : Component(project) {
  fun analyze(node: PklNode, diagnosticsHolder: MutableList<PklDiagnostic>) {
    if (node.isInStdlib) return
    if (!doAnalyze(node, diagnosticsHolder)) {
      return
    }
    node.children.forEach { analyze(it, diagnosticsHolder) }
  }

  /**
   * Collect diagnostics, pushing them into [diagnosticsHolder] as they are captured.
   *
   * Return `false` if the annotator does not need to analyze any further. This skips calling
   * [doAnalyze] on its children.
   */
  protected abstract fun doAnalyze(
    node: PklNode,
    diagnosticsHolder: MutableList<PklDiagnostic>,
  ): Boolean

  protected fun warn(
    node: PklNode,
    message: String,
    codeAction: PklCodeAction? = null,
  ): PklDiagnostic = PklDiagnostic(node, message, DiagnosticSeverity.Warning, codeAction)

  protected fun warn(
    span: Span,
    message: String,
    codeAction: PklCodeAction? = null,
  ): PklDiagnostic = PklDiagnostic(span, message, DiagnosticSeverity.Warning, codeAction)

  protected fun error(
    node: PklNode,
    message: String,
    codeAction: PklCodeAction? = null,
  ): PklDiagnostic = PklDiagnostic(node, message, DiagnosticSeverity.Error, codeAction)
}
