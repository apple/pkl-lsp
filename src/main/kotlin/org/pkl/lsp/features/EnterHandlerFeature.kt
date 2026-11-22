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
package org.pkl.lsp.features

import java.net.URI
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklDocComment
import org.pkl.lsp.ast.findBySpan
import org.pkl.lsp.getIndex

/**
 * Handles the `pkl/onEnter` request.
 *
 * When the caret is inside a doc comment, continues the doc comment on the next line: inserts a
 * newline, the current line's indent, and `/// `.
 *
 * Copies the behavior of pkl-intellij's `PklDocCommentEnterHandler`.
 */
class EnterHandlerFeature(project: Project) : Component(project) {
  private companion object {
    const val PREFIX = "///"
  }

  fun onEnter(params: TextDocumentPositionParams): CompletableFuture<Array<TextEdit>?> {
    val file =
      project.virtualFileManager.get(URI(params.textDocument.uri))
        ?: return CompletableFuture.completedFuture(null)
    return file.getModule().thenApply { module ->
      if (module == null) return@thenApply null
      val text = file.contents
      val caret = text.getIndex(params.position)
      val lineStart = text.lastIndexOf('\n', caret - 1) + 1
      val firstNonWs = skipWhitespace(text, lineStart)

      // The comment token must start this line, and the caret must be past it.
      if (!text.startsWith(PREFIX, firstNonWs)) return@thenApply null
      // Skip following spaces/tabs
      val offset = skipWhitespace(text, caret)
      if (offset < firstNonWs + PREFIX.length) return@thenApply null

      // Confirm via the parse tree that this is really a doc comment (not e.g. inside a string).
      val line = params.position.line
      val isDocComment =
        generateSequence(module.findBySpan(line + 1, firstNonWs - lineStart + 1)) { it.parent }
          .any { it is PklDocComment }
      if (!isDocComment) return@thenApply null

      val indent = text.substring(lineStart, firstNonWs)
      val edits = mutableListOf<TextEdit>()
      if (text.startsWith(PREFIX, offset)) {
        // Caret is right before an existing `///`; don't add another prefix. Just break the line
        // (dropping the whitespace in between), and ensure a space follows the prefix.
        edits += TextEdit(range(text, caret, offset), "\n$indent")
        val afterPrefix = offset + PREFIX.length
        if (afterPrefix < text.length && !text[afterPrefix].isWhitespace()) {
          edits += TextEdit(range(text, afterPrefix, afterPrefix), " ")
        }
      } else {
        // Add a space after the prefix unless the caret is already followed by one.
        val prefix = if (text.getOrNull(caret) != ' ') "$PREFIX " else PREFIX
        edits += TextEdit(range(text, caret, caret), "\n$indent$prefix")
      }
      edits.toTypedArray()
    }
  }

  private fun skipWhitespace(text: String, start: Int): Int {
    var i = start
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    return i
  }

  private fun range(text: String, start: Int, end: Int) =
    Range(position(text, start), position(text, end))

  private fun position(text: String, index: Int): Position {
    val lineStart = text.lastIndexOf('\n', index - 1) + 1
    val line = text.take(index).count { it == '\n' }
    return Position(line, index - lineStart)
  }
}
