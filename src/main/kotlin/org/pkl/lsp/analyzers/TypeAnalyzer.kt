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
import org.pkl.lsp.ast.PklDeclaredType
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.toType

class TypeAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, holder: DiagnosticsHolder): Boolean =
    when {
      node is PklDeclaredType && !node.typeArgumentList?.types.isNullOrEmpty() -> {
        val type = node.toType(project.pklBaseModule, emptyMap(), node.containingFile.pklProject)
        val argCount = node.typeArgumentList!!.types.size
        val paramCount =
          if (type is Type.Class) type.typeArguments.size
          else if (type is Type.Alias) type.typeArguments.size else 0
        if (paramCount != 0 && paramCount != argCount) {
          holder.addError(
            node,
            ErrorMessages.create("incorrectTypeArgumentCount", paramCount, argCount),
          )
        }
        false
      }
      else -> true
    }
}
