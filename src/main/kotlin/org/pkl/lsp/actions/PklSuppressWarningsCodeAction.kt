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
package org.pkl.lsp.actions

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.analyzers.PklDiagnostic
import org.pkl.lsp.analyzers.PklDiagnostic.Companion.getSuppression
import org.pkl.lsp.analyzers.PklProblemGroup
import org.pkl.lsp.ast.IdentifierOwner
import org.pkl.lsp.ast.PklSuppressWarningsTarget
import org.pkl.lsp.ast.Span
import org.pkl.lsp.ast.lspUri

class PklSuppressWarningsCodeAction(
  private val node: PklSuppressWarningsTarget,
  private val group: PklProblemGroup,
) : PklCodeAction {
  override val title: String
    get() =
      if (node is IdentifierOwner && node.identifier != null)
        "Suppress '${group.problemName}' for ${node.getKind()} '${node.identifier!!.text}'"
      else "Suppress '${group.problemName}' for ${node.getKind()}"

  override val kind: String = CodeActionKind.QuickFix

  // no need to set this if suppressed; we simply don't emit a diagnostic in that case.
  override val disabled: CodeActionDisabled? = null

  private fun addToExistingSuppression(suppression: PklDiagnostic.Companion.Suppression): TextEdit {
    return TextEdit().apply {
      range = suppression.lineComment.span.toRange()
      newText = "${suppression.lineComment.text},${group.problemName}"
    }
  }

  private fun addNewSuppression(): TextEdit {
    return TextEdit().apply {
      val lineText = node.containingFile.contents.lines()[node.span.beginLine - 1]
      val indent = lineText.takeWhile { it.isWhitespace() }
      val lineComment = "$indent// noinspection ${group.problemName}\n"
      val lineStart =
        Span(
          beginLine = node.span.beginLine,
          beginCol = 1,
          endLine = node.span.beginLine,
          endCol = 1,
        )
      range = lineStart.toRange()
      newText = lineComment
    }
  }

  private fun getEdit(): TextEdit {
    return node.getSuppression()?.let { addToExistingSuppression(it) } ?: addNewSuppression()
  }

  override fun toMessage(diagnostic: PklDiagnostic): CodeAction {
    val self = this
    return super.toMessage(diagnostic).apply {
      edit =
        WorkspaceEdit().apply {
          changes = mapOf(node.containingFile.lspUri.toString() to listOf(self.getEdit()))
        }
    }
  }
}
