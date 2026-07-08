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
package org.pkl.lsp

import org.pkl.lsp.ast.PklImport
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.SimpleModuleResolutionResult
import org.pkl.lsp.ast.Span
import org.pkl.lsp.ast.escapedText
import org.pkl.lsp.ast.memberName
import org.pkl.lsp.ast.resolve

data class ImportPresentation(val uri: String, val memberName: String, val alias: String?) :
  Comparable<ImportPresentation> {
  companion object {
    private val comparator: Comparator<ImportPresentation> =
      compareBy({ !it.hasSchema }, { it.uri }, { it.alias })
  }

  val hasSchema: Boolean = uri.contains(":")

  override fun compareTo(other: ImportPresentation): Int = comparator.compare(this, other)
}

data class ImportInfo(
  val presentation: ImportPresentation,
  val module: PklModule?,
  val span: Span,
) {
  companion object {
    fun create(import: PklImport): ImportInfo? {
      if (import.isGlob) return null
      val resolvedModule =
        (import.resolve(import.containingFile.pklProject) as SimpleModuleResolutionResult).resolved
      val presentation =
        ImportPresentation(
          import.moduleUri!!.stringConstant.escapedText()!!,
          import.memberName!!,
          import.identifierName,
        )
      return ImportInfo(presentation, resolvedModule, import.span)
    }
  }
}
