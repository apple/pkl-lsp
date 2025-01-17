/*
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
package org.pkl.lsp.features

import java.net.URI
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.documentation.toMarkdown
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.type.*

class HoverFeature(project: Project) : Component(project) {
  fun onHover(params: HoverParams): CompletableFuture<Hover> {
    fun run(mod: PklModule?): Hover {
      if (mod == null) return Hover(listOf())
      val context = mod.containingFile.pklProject
      val line = params.position.line + 1
      val col = params.position.character + 1
      val hoverText =
        mod.findBySpan(line, col)?.let { resolveHover(it, line, col, context) }
          ?: return Hover(listOf())
      return Hover(MarkupContent("markdown", hoverText))
    }
    val uri = URI(params.textDocument.uri)
    val file =
      project.virtualFileManager.get(uri)
        ?: return CompletableFuture.completedFuture(Hover(listOf()))
    return file.getModule().thenApply(::run)
  }

  private fun resolveHover(
    originalNode: PklNode,
    line: Int,
    col: Int,
    context: PklProject?,
  ): String? {
    val node = originalNode.resolveReference(line, col, context) ?: return null
    val base = project.pklBaseModule
    if (node !== originalNode) return node.toMarkdown(originalNode, context)
    return when (node) {
      is PklProperty -> node.toMarkdown(originalNode, context)
      is PklMethod -> node.toMarkdown(originalNode, context)
      is PklMethodHeader -> {
        val name = node.identifier
        // check if hovering over the method name
        if (name != null && name.span.matches(line, col)) {
          node.parent?.toMarkdown(originalNode, context)
        } else null
      }
      is PklClass -> {
        val name = node.identifier
        // check if hovering over the class name
        if (name != null && name.span.matches(line, col)) {
          node.toMarkdown(originalNode, context)
        } else null
      }
      is PklQualifiedIdentifier ->
        when (node.parent) {
          // render the module declaration
          is PklModuleClause -> node.enclosingModule?.toMarkdown(originalNode, context)
          else -> null
        }
      is PklDeclaredType -> node.toMarkdown(originalNode, context)
      is PklModule -> node.toMarkdown(originalNode, context)
      is PklTypeAlias -> {
        val name = node.identifier
        if (name != null && name.span.matches(line, col)) {
          node.toMarkdown(originalNode, context)
        } else null
      }
      is PklTypedIdentifier -> node.toMarkdown(originalNode, context)
      is PklThisExpr -> node.computeThisType(base, mapOf(), context).toMarkdown(project, context)
      is PklModuleExpr -> node.enclosingModule?.toMarkdown(originalNode, context)
      else -> null
    }
  }
}
