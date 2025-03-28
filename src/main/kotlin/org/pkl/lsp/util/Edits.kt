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
package org.pkl.lsp.util

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextEdit
import org.pkl.lsp.getIndex

object Edits {
  data class Edit(val range: Range?, val text: String)

  fun applyTextEdits(original: String, edits: List<TextEdit>): String {
    return apply(original, edits.map { it.toEdit() })
  }

  fun applyChangeEvents(original: String, edits: List<TextDocumentContentChangeEvent>): String {
    return apply(original, edits.map { it.toEdit() })
  }

  fun apply(original: String, edits: List<Edit>): String {
    var result = original
    for (change in edits) {
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

  private fun TextDocumentContentChangeEvent.toEdit(): Edit {
    return Edit(range = range, text = text)
  }

  private fun TextEdit.toEdit(): Edit {
    return Edit(range = range, text = newText)
  }
}
