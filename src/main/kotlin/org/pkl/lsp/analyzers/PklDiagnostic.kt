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

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.actions.PklCodeAction
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.Span

class PklDiagnostic(
  val span: Span,
  val message: String,
  val severity: DiagnosticSeverity,
  val action: PklCodeAction?,
) {
  constructor(
    node: PklNode,
    message: String,
    severity: DiagnosticSeverity,
    codeAction: PklCodeAction?,
  ) : this(node.span, message, severity, codeAction)

  fun toMessage(): Diagnostic = Diagnostic(span.toRange(), message, severity, "pkl")
}
