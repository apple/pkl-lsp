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
package org.pkl.lsp.completion

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.TextEdit
import org.pkl.lsp.Component
import org.pkl.lsp.ImportInfo
import org.pkl.lsp.ImportInfo.Companion.create
import org.pkl.lsp.Project
import org.pkl.lsp.Refactorings
import org.pkl.lsp.ast.PklClassBody
import org.pkl.lsp.ast.PklClassMethod
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklTypeAlias
import org.pkl.lsp.ast.PklTypeDefOrModule
import org.pkl.lsp.ast.appendIdentifier
import org.pkl.lsp.ast.hasDeclaredMethod
import org.pkl.lsp.ast.methods
import org.pkl.lsp.ast.parentOfType

class ImplementMethodCompletionProvider(project: Project) : Component(project), CompletionProvider {
  override fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
  ) {
    if (node.parent !is PklClassBody && node.parent !is PklModule) return
    val def = node.parentOfType<PklTypeDefOrModule>() ?: return
    if (def is PklTypeAlias) return
    val module = def.enclosingModule ?: return
    val context = def.containingFile.pklProject
    // only offer completions for abstract methods for now.
    // completions for other methods might encourage overriding functions that are meant to be
    // closed
    // (Pkl does not have open/closed functions)
    val parentMethods =
      def.methods(context)?.values?.filterNot { def.hasDeclaredMethod(it.name) || !it.isAbstract }
        ?: return

    for (method in parentMethods) {
      if (method.identifierName == null) continue
      collector.add(
        CompletionItem().apply {
          val insertedImports = mutableListOf<TextEdit>()
          val imports = module.imports.mapNotNull(ImportInfo::create).toMutableList()
          val memberText =
            Refactorings.renderMember(module, method, imports, insertedImports, useSnippets = true)
          val effectiveDocComment = method.effectiveDocComment(context)
          this.label = renderCompletionText(method)
          this.kind = CompletionItemKind.Method
          this.insertTextFormat = InsertTextFormat.Snippet
          this.insertText = memberText
          this.additionalTextEdits = insertedImports
          if (effectiveDocComment != null) {
            setDocumentation(MarkupContent(effectiveDocComment, MarkupKind.MARKDOWN))
          }
        }
      )
    }
  }

  fun renderCompletionText(method: PklClassMethod): String {
    return buildString {
      append("function ")
      // `name` guaranteed to exist (we only provide completions for members that have names)
      appendIdentifier(method.identifierName!!)
      append("(")
      method.methodHeader.parameterList?.elements?.let { params ->
        var isFirst = true
        for (param in params) {
          if (isFirst) {
            isFirst = false
          } else {
            append(", ")
          }
          appendIdentifier(param.identifierName!!)
          param.type?.let { type ->
            append(": ")
            append(type.text)
          }
        }
      }
      append(")")
      method.methodHeader.returnType?.let { type ->
        append(": ")
        append(type.text)
      }
      append(" = …")
    }
  }
}
