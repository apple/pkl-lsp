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

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.Component
import org.pkl.lsp.LSPUtil.toRange
import org.pkl.lsp.PklLSPServer
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.type.computeThisType

class GoToDefinitionFeature(private val server: PklLSPServer, project: Project) :
  Component(project) {

  fun onGoToDefinition(
    params: DefinitionParams
  ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
    fun run(mod: PklModule?): Either<List<Location>, List<LocationLink>> {
      if (mod == null) return Either.forLeft(listOf())
      val line = params.position.line + 1
      val col = params.position.character + 1
      val location =
        mod.findBySpan(line, col)?.let { resolveDeclaration(it, line, col) }
          ?: return Either.forLeft(listOf())
      return Either.forLeft(listOf(location))
    }
    return server.builder().runningBuild(params.textDocument.uri).thenApply(::run)
  }

  private fun resolveDeclaration(originalNode: PklNode, line: Int, col: Int): Location? {
    val node = originalNode.resolveReference(line, col) ?: return null
    return when (node) {
      is PklThisExpr ->
        node.computeThisType(project.pklBaseModule, mapOf()).getNode(project)?.toLocation()
      else -> if (node !== originalNode) node.toLocation() else null
    }
  }

  private fun PklNode.toLocation(): Location {
    return Location(toURIString(), beginningSpan().toRange())
  }
}
