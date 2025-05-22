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

import java.util.EnumSet
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.TextEdit
import org.pkl.lsp.ast.PklModifierListOwner
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.TokenType

class PklAddModifierQuickFix(
  override val node: PklModifierListOwner,
  override val title: String,
  private val modifier: TokenType,
) : PklLocalEditCodeAction(node) {
  init {
    require(validModifiers.contains(modifier)) { "$modifier is not a valid modifier" }
  }

  override fun getEdits(): List<TextEdit> {
    return listOf(insertBefore(getAnchor(), modifier.sourceCode + " "))
  }

  private fun getAnchor(): PklNode {
    val sortOrder = modifier.sortOrder
    return node.modifiers?.find { it.type.sortOrder >= sortOrder } ?: node.terminals.first()
  }

  override val kind: String = CodeActionKind.QuickFix

  override val disabled: CodeActionDisabled? = null

  companion object {
    private val validModifiers =
      EnumSet.of(
        TokenType.ABSTRACT,
        TokenType.OPEN,
        TokenType.FIXED,
        TokenType.CONST,
        TokenType.HIDDEN,
        TokenType.LOCAL,
        TokenType.EXTERNAL,
      )
  }
}

private val TokenType.sortOrder: Int
  get() =
    when (this) {
      TokenType.ABSTRACT -> 0
      TokenType.OPEN -> 0
      TokenType.FIXED -> 0
      TokenType.CONST -> 0
      TokenType.HIDDEN -> 1
      TokenType.LOCAL -> 1
      TokenType.EXTERNAL -> 2
      else -> 3
    }

private val TokenType.sourceCode: String
  get() =
    when (this) {
      TokenType.ABSTRACT -> "abstract"
      TokenType.OPEN -> "open"
      TokenType.FIXED -> "fixed"
      TokenType.CONST -> "const"
      TokenType.HIDDEN -> "hidden"
      TokenType.LOCAL -> "local"
      TokenType.EXTERNAL -> "external"
      else -> throw AssertionError("Unknown modifier $this")
    }
