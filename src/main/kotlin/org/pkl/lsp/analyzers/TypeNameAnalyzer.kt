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
package org.pkl.lsp.analyzers

import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklTypeName
import org.pkl.lsp.ast.resolve

class TypeNameAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, holder: DiagnosticsHolder): Boolean {
    when (node) {
      is PklTypeName -> {
        val moduleName = node.moduleName
        val context = node.containingFile.pklProject
        if (moduleName != null) {
          val resolvedModule = moduleName.resolve(context)
          if (resolvedModule == null) {
            moduleName.identifier?.let { identifier ->
              holder.addError(
                moduleName,
                ErrorMessages.create("unresolvedReference", identifier.text),
              )
            }
            return false
          }
        }
        val typeName = node.simpleTypeName
        val resolvedType = typeName.resolve(context)
        if (resolvedType == null) {
          typeName.identifier?.let { identifier ->
            holder.addError(
              node.simpleTypeName,
              ErrorMessages.create("unresolvedReference", identifier.text),
            )
          }
          return false
        }
      }
    }
    return true
  }
}
