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
package org.pkl.lsp

import java.net.URI
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.pkl.lsp.features.CodeActionFeature
import org.pkl.lsp.features.CompletionFeature
import org.pkl.lsp.features.GoToDefinitionFeature
import org.pkl.lsp.features.HoverFeature
import org.pkl.lsp.services.Topic

val textDocumentTopic = Topic<TextDocumentEvent>("TextDocumentEvent")

sealed interface TextDocumentEvent {
  val file: URI

  data class Opened(override val file: URI) : TextDocumentEvent

  data class Closed(override val file: URI) : TextDocumentEvent

  data class Saved(override val file: URI) : TextDocumentEvent

  data class Changed(override val file: URI, val changes: List<TextDocumentContentChangeEvent>) :
    TextDocumentEvent
}

class PklTextDocumentService(project: Project) : Component(project), TextDocumentService {

  private val hover = HoverFeature(project)
  private val definition = GoToDefinitionFeature(project)
  private val completion = CompletionFeature(project)
  private val codeAction = CodeActionFeature(project)

  override fun didOpen(params: DidOpenTextDocumentParams) {
    val uri = URI(params.textDocument.uri)
    project.virtualFileManager.get(uri)?.let { file ->
      file.version = params.textDocument.version.toLong()
    }
    project.messageBus.emit(textDocumentTopic, TextDocumentEvent.Opened(uri))
  }

  override fun didChange(params: DidChangeTextDocumentParams) {
    val uri = URI(params.textDocument.uri)
    // update contents first before emitting this message, to ensure that downstream handlers
    // receive
    // up-to-date versions of each VirtualFile.
    project.virtualFileManager.get(uri)?.let { file ->
      file.contents = replaceContents(file.contents, params.contentChanges)
      file.version = params.textDocument.version.toLong()
    }
    project.messageBus.emit(
      textDocumentTopic,
      TextDocumentEvent.Changed(uri, params.contentChanges),
    )
  }

  override fun didClose(params: DidCloseTextDocumentParams) {
    val uri = URI(params.textDocument.uri)
    project.messageBus.emit(textDocumentTopic, TextDocumentEvent.Closed(uri))
  }

  override fun didSave(params: DidSaveTextDocumentParams) {
    val uri = URI(params.textDocument.uri)
    project.messageBus.emit(textDocumentTopic, TextDocumentEvent.Saved(uri))
  }

  override fun hover(params: HoverParams): CompletableFuture<Hover> {
    return hover.onHover(params)
  }

  override fun definition(
    params: DefinitionParams
  ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
    return definition.onGoToDefinition(params)
  }

  override fun completion(
    params: CompletionParams
  ): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
    return completion.onCompletion(params)
  }

  override fun codeAction(
    params: CodeActionParams
  ): CompletableFuture<List<Either<Command, CodeAction>>> {
    return codeAction.onCodeAction(params)
  }

  private fun replaceContents(
    contents: String,
    changes: List<TextDocumentContentChangeEvent>,
  ): String {
    var result = contents
    for (change in changes) {
      if (change.range == null) {
        result = change.text
      } else {
        val startIndex = result.getIndex(change.range.start)
        val endIndex = result.getIndex(change.range.end)
        result = result.replaceRange(startIndex, endIndex, change.text)
      }
    }
    return result
  }
}
