/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.Span

class DiagnosticsHolder {
  private val myDiagnostics: MutableList<PklDiagnostic> = mutableListOf()

  val diagnostics: List<PklDiagnostic> = myDiagnostics

  fun addWarning(
    node: PklNode,
    message: String,
    span: Span = node.span,
    action: PklDiagnostic.() -> Unit = {},
  ) = addDiagnostic(node, message, span, DiagnosticSeverity.Warning, action)

  fun addError(
    node: PklNode,
    message: String,
    span: Span = node.span,
    action: PklDiagnostic.() -> Unit = {},
  ) = addDiagnostic(node, message, span, DiagnosticSeverity.Error, action)

  fun addDiagnostic(
    node: PklNode,
    message: String,
    span: Span = node.span,
    severity: DiagnosticSeverity? = null,
    action: PklDiagnostic.() -> Unit = {},
  ) {
    val diag = PklDiagnostic(span, message, severity).also { action(it) }
    doAddDiagnostic(node, diag)
  }

  private fun doAddDiagnostic(node: PklNode, diagnostic: PklDiagnostic) {
    if (diagnostic.isSuppressed(node)) {
      return
    }
    diagnostic.problemGroup?.let { problemGroup ->
      // copy logic in pkl-intellij; only support warning suppression for non-errors.
      if (diagnostic.severity != DiagnosticSeverity.Error) {
        diagnostic.actions += problemGroup.getSuppressQuickFixes(node)
      }
    }
    myDiagnostics += diagnostic
  }
}
