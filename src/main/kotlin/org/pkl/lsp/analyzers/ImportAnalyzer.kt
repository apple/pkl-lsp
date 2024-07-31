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
package org.pkl.lsp.analyzers

import java.net.URI
import org.pkl.lsp.CacheManager
import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.PklLSPServer
import org.pkl.lsp.Stdlib
import org.pkl.lsp.ast.Node
import org.pkl.lsp.ast.PklImportBase
import org.pkl.lsp.ast.escapedText

class ImportAnalyzer(private val server: PklLSPServer) : Analyzer() {

  override fun doAnalyze(node: Node, diagnosticsHolder: MutableList<PklDiagnostic>): Boolean {
    if (node !is PklImportBase) {
      return true
    }

    val uriStr = node.moduleUri?.stringConstant?.escapedText() ?: return true

    when {
      // validate stdlib imports
      uriStr.startsWith("pkl:") -> {
        val name = uriStr.replace("pkl:", "")
        if (Stdlib.getModule(name) == null) {
          diagnosticsHolder += error(node, ErrorMessages.create("invalidStdlibImport"))
        }
      }

      // validate https imports
      uriStr.startsWith("https://") -> {
        try {
          val uri = URI.create(uriStr)
          if (CacheManager.findHttpModule(uri) == null) {
            diagnosticsHolder += error(node, ErrorMessages.create("invalidHttpsUrlImport"))
          }
        } catch (_: IllegalArgumentException) {
          diagnosticsHolder += error(node, ErrorMessages.create("invalidHttpsPklImport"))
        }
      }
    }
    return true
  }
}
