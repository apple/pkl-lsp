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
package org.pkl.lsp.actions

import java.util.Collections
import java.util.IdentityHashMap
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.pkl.lsp.ast.PklImport
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.resolvers.visitUsedLocalDefinitions

class PklRemoveUnusedImportsQuickFix(override val node: PklModule) : PklLocalEditCodeAction(node) {
  override fun getEdits(): List<TextEdit> {
    return buildList {
      for (import in unusedImports(node)) {
        add(
          TextEdit().apply {
            val startPosition = Position(import.span.beginLine - 1, import.span.beginCol - 1)
            val endPosition = Position(import.span.beginLine, 0)
            range = Range(startPosition, endPosition)
            newText = ""
          }
        )
      }
    }
  }

  override val title: String = "Remove unused imports"

  override val kind: String = CodeActionKind.QuickFix

  override val disabled: CodeActionDisabled? = null

  private fun unusedImports(module: PklModule): List<PklImport> {
    val base = module.project.pklBaseModule
    val usedImports: MutableSet<PklImport> = Collections.newSetFromMap(IdentityHashMap())
    visitUsedLocalDefinitions(module, base) { if (it is PklImport) usedImports.add(it) }
    return module.imports.filter { !usedImports.contains(it) }.toList()
  }
}
