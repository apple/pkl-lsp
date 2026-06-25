/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.sequence
import org.pkl.lsp.type.computeThisType

class GoToDefinitionFeature(project: Project) : Component(project) {
  companion object {
    val NO_COMPLETIONS: CompletableFuture<Either<List<Location>, List<LocationLink>>> =
      CompletableFuture.completedFuture(Either.forLeft(emptyList()))
  }

  fun onGoToDefinition(
    params: DefinitionParams
  ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
    val uri = URI(params.textDocument.uri)
    val file =
      project.virtualFileManager.get(uri)
        ?: return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    return file.getModule().thenCompose { doGoToDefinition(it, params) }
  }

  private fun doGoToDefinition(
    module: PklModule?,
    params: DefinitionParams,
  ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
    if (module == null) return NO_COMPLETIONS
    val originalNode =
      module.findBySpan(params.position.line + 1, params.position.character + 1)
        ?: return NO_COMPLETIONS
    val context = module.containingFile.pklProject
    val parent = originalNode.parent
    return when {
      originalNode is PklStringConstant && parent is PklImportBase ->
        if (parent.isGlob) resolveGlobModuleUri(originalNode, parent, context)
        else
          CompletableFuture.completedFuture(
            Either.forRight(resolveNormalModuleUri(originalNode, parent, context))
          )
      else ->
        CompletableFuture.completedFuture(
          Either.forLeft(
            resolveReference(
              originalNode,
              params.position.line + 1,
              params.position.character + 1,
              context,
            )
          )
        )
    }
  }

  private fun resolveGlobModuleUri(
    originalNode: PklStringConstant,
    parent: PklModuleUriOwner,
    context: PklProject?,
  ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
    val stringContentsSpan = originalNode.contentsSpan()
    val resolved = parent.moduleUri?.resolveGlob(context) ?: return NO_COMPLETIONS
    val futures = buildList {
      for (file in resolved.elements) {
        if (!file.isDirectory) {
          add(file.getModule())
        }
      }
    }
    return futures.sequence().thenApply { elems ->
      Either.forRight(elems.mapNotNull { it?.locationLink(stringContentsSpan) })
    }
  }

  private fun resolveNormalModuleUri(
    originalNode: PklStringConstant,
    parent: PklModuleUriOwner,
    context: PklProject?,
  ): List<LocationLink> {
    val stringContentsSpan = originalNode.contentsSpan()
    val resolved = parent.moduleUri?.resolve(context) ?: return listOf()
    return listOf(resolved.locationLink(stringContentsSpan))
  }

  private fun resolveReference(
    originalNode: PklNode,
    line: Int,
    col: Int,
    context: PklProject?,
  ): List<Location> {
    val node = originalNode.resolveReference(line, col, context) ?: return emptyList()
    return when (node) {
      is PklThisExpr ->
        node
          .computeThisType(project.pklBaseModule, mapOf(), context)
          .getNode(project, context)
          ?.let { listOf(it.location) } ?: emptyList()
      is PklReferenceQualifiedAccessProxy -> node.classProperties.map { it.location }
      else -> if (node !== originalNode) listOf(node.location) else emptyList()
    }
  }
}
