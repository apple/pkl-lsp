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
  override fun doAnalyze(node: PklNode, holder: DiagnosticsHolder): Boolean =
    when {
      node is PklTypeName -> {
        val context = node.containingFile.pklProject
        when {
          node.moduleName != null && node.moduleName!!.resolve(context) == null -> {
            node.moduleName!!.identifier?.let {
              holder.addError(
                node.moduleName!!,
                ErrorMessages.create("unresolvedReference", it.text),
              )
            }
            false
          }
          node.simpleTypeName.resolve(context) == null -> {
            node.simpleTypeName.identifier?.let {
              holder.addError(
                node.simpleTypeName,
                ErrorMessages.create("unresolvedReference", it.text),
              )
            }
            false
          }
          else -> true
        }
      }
      else -> true
    }
}
