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
package org.pkl.lsp.actions

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.analyzers.PklDiagnostic
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.lspUri

sealed interface PklCodeAction {
  val title: String

  val kind: String

  val disabled: CodeActionDisabled?

  fun toMessage(diagnostic: PklDiagnostic): CodeAction {
    val self = this
    return CodeAction().apply {
      title = self.title
      kind = self.kind
      disabled = self.disabled
      isPreferred = true
      diagnostics = listOf(diagnostic.toMessage())
    }
  }
}

abstract class PklCommandCodeAction(val commandId: String, val arguments: List<Any>) :
  PklCodeAction {
  final override fun toMessage(diagnostic: PklDiagnostic): CodeAction {
    val self = this
    return super.toMessage(diagnostic).apply {
      command =
        Command().apply {
          title = self.title
          command = self.commandId
          arguments = self.arguments
        }
    }
  }
}

/** Changes code locally within the enclosing file of [node]. */
abstract class PklLocalEditCodeAction(protected open val node: PklNode) : PklCodeAction {
  protected abstract fun getEdits(): List<TextEdit>

  override fun toMessage(diagnostic: PklDiagnostic): CodeAction {
    return super.toMessage(diagnostic).apply {
      edit =
        WorkspaceEdit().apply {
          changes = mapOf(node.containingFile.lspUri.toString() to getEdits())
        }
    }
  }

  protected fun insertBefore(anchor: PklNode, newText: String): TextEdit {
    return TextEdit().apply {
      this.range = anchor.span.firstCaret().toRange()
      this.newText = newText
    }
  }
}
