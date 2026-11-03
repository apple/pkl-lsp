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
package org.pkl.lsp.actions

import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.TextEdit
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.ast.PklAccessExpr
import org.pkl.lsp.ast.Span
import org.pkl.lsp.ast.memberAccessSpan

class PklParenthesizeComparisonQuickFix(override val node: PklAccessExpr) :
  PklLocalEditCodeAction(node) {
  override fun getEdits(): List<TextEdit> {
    val memberAccessSpan = node.memberAccessSpan
    val startingSpan =
      Span(
        memberAccessSpan.beginLine,
        memberAccessSpan.beginCol,
        memberAccessSpan.beginLine,
        memberAccessSpan.beginCol,
      )
    val endingSpan =
      Span(
        memberAccessSpan.endLine,
        memberAccessSpan.endCol,
        memberAccessSpan.endLine,
        memberAccessSpan.endCol,
      )
    return listOf(
      TextEdit().apply {
        range = endingSpan.toRange()
        newText = ")"
      },
      TextEdit().apply {
        range = startingSpan.toRange()
        newText = "("
      },
    )
  }

  override val title: String = "Wrap left-hand side with parentheses"

  override val kind: String = CodeActionKind.QuickFix

  override val disabled: CodeActionDisabled? = null
}
