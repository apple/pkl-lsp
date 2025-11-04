/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.pkl.formatter.Formatter
import org.pkl.formatter.GrammarVersion
import org.pkl.lsp.Component
import org.pkl.lsp.Project

class FormattingFeature(project: Project) : Component(project) {
  private val formatter = Formatter()

  private val maxRange = Range(Position(0, 0), Position(Int.MAX_VALUE, Int.MAX_VALUE))

  fun onFormat(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
    val uri = URI(params.textDocument.uri)
    val file =
      project.virtualFileManager.get(uri) ?: return CompletableFuture.completedFuture(emptyList())
    val grammarVersion = project.settingsManager.settings.grammarVersion ?: GrammarVersion.latest()
    val formatted = formatter.format(file.contents, grammarVersion)
    val edit =
      TextEdit().apply {
        this.newText = formatted
        this.range = maxRange
      }
    return CompletableFuture.completedFuture(listOf(edit))
  }
}
