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
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.pkl.lsp.ast.PklNode

class PklRemoveRemoveNodeQuickFix(override val title: String, override val node: PklNode) :
  PklLocalEditCodeAction(node) {
  override fun getEdits(): List<TextEdit> {
    return buildList {
      add(
        TextEdit().apply {
          val startPosition = Position(node.span.beginLine - 1, node.span.beginCol - 1)
          val nextSibling = node.nextSiblingTotal()
          val endPosition =
            if (nextSibling != null)
              Position(nextSibling.span.beginLine - 1, nextSibling.span.beginCol - 1)
            else Position(node.span.endLine - 1, node.span.endCol - 1)
          range = Range(startPosition, endPosition)
          newText = ""
        }
      )
    }
  }

  override val kind: String = CodeActionKind.QuickFix

  override val disabled: CodeActionDisabled? = null
}
