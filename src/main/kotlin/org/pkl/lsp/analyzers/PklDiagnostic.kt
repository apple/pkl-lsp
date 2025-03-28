/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.eclipse.lsp4j.DiagnosticTag
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.actions.PklCodeAction
import org.pkl.lsp.ast.PklLineComment
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklSuppressWarningsTarget
import org.pkl.lsp.ast.Span
import org.pkl.lsp.ast.parentsOfType

class PklDiagnostic(
  var span: Span,
  var message: String,
  var severity: DiagnosticSeverity? = null,
  var actions: List<PklCodeAction> = emptyList(),
  var tags: List<DiagnosticTag> = emptyList(),
  var problemGroup: PklProblemGroup? = null,
) {
  val id: String
    get() = "$span $message"

  fun toMessage(): Diagnostic =
    Diagnostic(span.toRange(), message, severity, "pkl").also {
      it.tags = tags
      it.data = id
    }

  fun isSuppressed(node: PklNode): Boolean {
    // copy logic in pkl-intellij; error diagnostics cannot be suppressed
    if (severity == DiagnosticSeverity.Error) return false
    val problemName = problemGroup?.problemName ?: return false
    for (member in node.parentsOfType<PklSuppressWarningsTarget>()) {
      if (member.getSuppression()?.isSuppressed(problemName) == true) {
        return true
      }
    }
    return false
  }

  companion object {
    // taken from com.intellij.codeInspection.SuppressionUtil#COMMON_SUPPRESS_REGEXP
    val suppressionRegex =
      Regex("\\s*noinspection\\s+([a-zA-Z_0-9.-]+(\\s*,\\s*[a-zA-Z_0-9.-]+)*)\\s*\\w*")

    fun PklSuppressWarningsTarget.getSuppression(): Suppression? {
      val comment = prevSibling() as? PklLineComment ?: return null
      return suppressionRegex.find(comment.text, 2)?.let { matchResult ->
        Suppression(
          lineComment = comment,
          problemGroups = matchResult.groupValues[1].split(Regex("[, ]")),
        )
      }
    }

    data class Suppression(val lineComment: PklLineComment, val problemGroups: List<String>) {
      fun isSuppressed(problemName: String) = problemGroups.contains(problemName)
    }
  }
}
