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
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.Builder
import org.pkl.lsp.Component
import org.pkl.lsp.LSPUtil.toRange
import org.pkl.lsp.PklLSPServer
import org.pkl.lsp.Project

class CodeActionFeature(private val server: PklLSPServer, project: Project) : Component(project) {

  fun onCodeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
    return server.builder().runningBuild(params.textDocument.uri).thenApply {
      val diagnostics =
        Builder.diagnosticsCache[URI(params.textDocument.uri)]
          ?: return@thenApply emptyList<Either<Command, CodeAction>>()
      diagnostics
        .filter { it.span.toRange() == params.range }
        .mapNotNull { diagnostic ->
          diagnostic.action?.let { Either.forRight(it.toMessage(diagnostic)) }
        }
    }
  }
}
