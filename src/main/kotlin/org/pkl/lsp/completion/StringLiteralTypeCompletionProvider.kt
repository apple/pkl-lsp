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
package org.pkl.lsp.completion

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.Component
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.documentation.toMarkdown
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.inferExprTypeFromContext

class StringLiteralTypeCompletionProvider(project: Project) :
  Component(project), CompletionProvider {
  override fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
  ) {
    val stringLiteral = getStringLiteral(node) ?: return
    val context = node.containingFile.pklProject
    val resultType = stringLiteral.inferExprTypeFromContext(project.pklBaseModule, mapOf(), context)
    addCompletionResults(resultType, stringLiteral, collector, null, project.pklBaseModule, context)
  }

  // only handle completions for single line string literals (multiline string literals are harder
  // to insert, not likely to be used for enum members
  private fun getStringLiteral(node: PklNode): PklSingleLineStringLiteral? {
    return node as? PklSingleLineStringLiteral
      ?: node.parentOfTypes(
        PklSingleLineStringLiteral::class,
        /* stop class */ PklObjectBody::class,
      ) as? PklSingleLineStringLiteral
  }

  private fun addCompletionResults(
    resultType: Type,
    stringLiteral: PklSingleLineStringLiteral,
    result: MutableList<CompletionItem>,
    originalAlias: Type.Alias?,
    base: PklBaseModule,
    context: PklProject?,
  ) {

    when (resultType) {
      is Type.StringLiteral -> {
        val startDelimiter = stringLiteral.terminals.first()
        result.add(
          CompletionItem().apply {
            textEdit =
              Either.forLeft(
                TextEdit().apply {
                  newText = resultType.value
                  range = stringLiteral.contentsSpan().toRange()
                }
              )
            label = resultType.render(startDelimiter.text)
            labelDetails =
              CompletionItemLabelDetails().apply {
                val detailedType = originalAlias ?: base.stringType
                detail = detailedType.render()
                documentation =
                  Either.forRight(
                    MarkupContent().apply {
                      this.kind = MarkupKind.MARKDOWN
                      this.value = detailedType.toMarkdown(project, context)
                    }
                  )
              }
            kind = CompletionItemKind.EnumMember
          }
        )
      }
      is Type.Union -> {
        resultType.eachElementType { type ->
          addCompletionResults(type, stringLiteral, result, originalAlias, base, context)
        }
      }
      is Type.Alias -> {
        addCompletionResults(
          resultType.aliasedType(base, context),
          stringLiteral,
          result,
          resultType,
          base,
          context,
        )
      }
      else -> {}
    }
  }
}
