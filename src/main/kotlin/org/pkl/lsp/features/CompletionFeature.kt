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
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.completion.*

class CompletionFeature(project: Project) : Component(project) {
  private val completionProviders: List<CompletionProvider> =
    listOf(
      ModuleUriCompletionProvider(project, false),
      QualifiedAccessCompletionProvider(project),
      UnqualifiedAccessCompletionProvider(project),
      StringLiteralTypeCompletionProvider(project),
    )

  fun onCompletion(
    params: CompletionParams
  ): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
    fun run(mod: PklModule?): Either<List<CompletionItem>, CompletionList> {
      if (mod == null) {
        return Either.forLeft(emptyList())
      }
      val line = params.position.line + 1
      val col = params.position.character + 1
      val span = mod.findBySpan(line, col) ?: return Either.forLeft(emptyList())
      val collector = mutableListOf<CompletionItem>()
      for (provider in completionProviders) {
        provider.getCompletions(span, params, collector)
      }
      return Either.forLeft(collector)
    }
    val uri = URI(params.textDocument.uri)
    val file =
      project.virtualFileManager.get(uri)
        ?: return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    return file.getModule().thenApply(::run)
  }

  fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
    for (provider in completionProviders) {
      provider.resolveCompletionItem(unresolved)?.let {
        return it
      }
    }
    return CompletableFuture.completedFuture(unresolved)
  }
}
