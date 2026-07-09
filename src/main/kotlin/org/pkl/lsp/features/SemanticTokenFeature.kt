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
package org.pkl.lsp.features

import java.net.URI
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import org.pkl.lsp.Component
import org.pkl.lsp.PklRecursiveVisitor
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklDocComment
import org.pkl.lsp.ast.Span

class SemanticTokenFeature(project: Project) : Component(project) {
  companion object {
    enum class SemanticTokenType(val tokenType: String) {
      Property(SemanticTokenTypes.Property)
    }

    enum class SemanticTokenModifier(val modifier: String, val value: Int) {
      Documentation(SemanticTokenModifiers.Documentation, 1)
    }

    // most correct colors are already provided by tree-sitter based syntax highlighting
    enum class Color(
      val semanticTokenType: SemanticTokenType,
      val modifiers: Set<SemanticTokenModifier>,
    ) {
      DocCommentMemberLink(
        SemanticTokenType.Property,
        EnumSet.of(SemanticTokenModifier.Documentation),
      )
    }

    val legend =
      SemanticTokensLegend().apply {
        tokenTypes = SemanticTokenType.entries.mapTo(mutableListOf()) { it.tokenType }
        tokenModifiers = SemanticTokenModifier.entries.mapTo(mutableListOf()) { it.modifier }
      }
    private val empty = SemanticTokens()
  }

  fun onSemanticToken(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
    val uri = URI(params.textDocument.uri)
    val file =
      project.virtualFileManager.get(uri) ?: return CompletableFuture.completedFuture(empty)
    return file.getModule().thenApply { module ->
      if (module == null) return@thenApply empty
      val tokens = mutableListOf<Int>()
      var prevSpan: Span? = null
      module.accept(
        object : PklRecursiveVisitor<Unit>() {
          override fun visitDocComment(node: PklDocComment) {
            val links = node.memberLinks
            for (link in links) {
              // copy Kotlin and include `[]` in the highlighting
              val span =
                Span(
                  link.span.beginLine,
                  link.span.beginCol - 1,
                  link.span.endLine,
                  link.span.endCol + 1,
                )
              tokens.addSpan(link.reference.length + 2, prevSpan, span, Color.DocCommentMemberLink)
              prevSpan = span
            }
          }
        }
      )
      SemanticTokens(tokens)
    }
  }

  private fun MutableList<Int>.addSpan(length: Int, prevSpan: Span?, span: Span, color: Color) {
    val deltaLine =
      if (prevSpan == null) span.beginLine - 1 else span.beginLine - prevSpan.beginLine
    val deltaColumn =
      if (prevSpan?.beginLine == span.beginLine) span.beginCol - prevSpan.beginCol
      else span.beginCol - 1
    add(deltaLine)
    add(deltaColumn)
    add(length)
    add(color.getTokenType())
    add(color.getTokenModifier())
  }

  fun Color.getTokenType(): Int {
    return semanticTokenType.ordinal
  }

  fun Color.getTokenModifier(): Int {
    var ret = 0
    for (mod in modifiers) {
      ret = ret.or(mod.ordinal)
    }
    return ret
  }
}
