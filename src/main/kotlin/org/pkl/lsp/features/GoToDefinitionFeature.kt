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
package org.pkl.lsp.features

import java.net.URI
import java.util.*
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.Component
import org.pkl.lsp.LSPUtil.toRange
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.type.computeThisType

class GoToDefinitionFeature(project: Project) : Component(project) {

  companion object {
    private val quoteCharacters =
      EnumSet.of(TokenType.SLQuote, TokenType.SLEndQuote, TokenType.MLQuote, TokenType.MLEndQuote)

    private fun PklStringConstant.contentsSpan(): Span {
      val characters =
        terminals
          .dropWhile { quoteCharacters.contains(it.type) }
          .dropLastWhile { quoteCharacters.contains(it.type) }
      val firstSpan = characters.first().span
      val lastSpan = characters.last().span
      return Span(firstSpan.beginLine, firstSpan.beginCol, lastSpan.endLine, lastSpan.endCol)
    }
  }

  fun onGoToDefinition(
    params: DefinitionParams
  ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
    fun run(mod: PklModule?): Either<List<Location>, List<LocationLink>> {
      if (mod == null) return Either.forRight(listOf())
      val line = params.position.line + 1
      val col = params.position.character + 1
      val context = mod.containingFile.pklProject
      val node = mod.findBySpan(line, col) ?: return Either.forRight(listOf())
      return resolveDeclarations(node, line, col, context)
    }
    val uri = URI(params.textDocument.uri)
    val file =
      project.virtualFileManager.get(uri)
        ?: return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    return file.getModule().thenApply(::run)
  }

  private fun resolveModuleUri(
    originalNode: PklStringConstant,
    parent: PklModuleUriOwner,
    context: PklProject?,
  ): List<LocationLink> {
    val stringContentsSpan = originalNode.contentsSpan()
    if (parent is PklImportBase && parent.isGlob) {
      val resolved = parent.moduleUri?.resolveGlob(context) ?: return listOf()
      return resolved.map { it.toLocationLink(stringContentsSpan) }
    }
    val resolved = parent.moduleUri?.resolve(context) ?: return listOf()
    return listOf(resolved.toLocationLink(stringContentsSpan))
  }

  private fun resolveDeclarations(
    originalNode: PklNode,
    line: Int,
    col: Int,
    context: PklProject?,
  ): Either<List<Location>, List<LocationLink>> {
    if (originalNode is PklStringConstant && originalNode.parent is PklModuleUriOwner) {
      return Either.forRight(
        resolveModuleUri(originalNode, originalNode.parent as PklModuleUriOwner, context)
      )
    }
    val node =
      originalNode.resolveReference(line, col, context) ?: return Either.forRight(emptyList())
    val ret =
      when (node) {
        is PklThisExpr ->
          node
            .computeThisType(project.pklBaseModule, mapOf(), context)
            .getNode(project, context)
            ?.let { listOf(it.location) } ?: emptyList()
        else -> if (node !== originalNode) listOf(node.location) else emptyList()
      }
    return Either.forLeft(ret)
  }

  private fun PklNode.toLocationLink(originalSpan: Span): LocationLink {
    val range = beginningSpan().toRange()
    return LocationLink(toLspURIString(), range, range, originalSpan.toRange())
  }
}
