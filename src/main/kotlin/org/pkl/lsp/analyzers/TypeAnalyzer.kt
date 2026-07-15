/*
 * Copyright © 2025-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklAnnotation
import org.pkl.lsp.ast.PklClassBody
import org.pkl.lsp.ast.PklDeclaredType
import org.pkl.lsp.ast.PklModuleType
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklThisType
import org.pkl.lsp.ast.PklTypeAlias
import org.pkl.lsp.ast.parentOfType
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.toType

class TypeAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, diagnosticsHolder: DiagnosticsHolder): Boolean =
    when {
      node is PklDeclaredType -> {
        validateDeclaredType(node, diagnosticsHolder)
        false
      }
      node is PklModuleType -> {
        validateModuleType(node, diagnosticsHolder)
        false
      }
      node is PklThisType -> {
        validateThisType(node, diagnosticsHolder)
        false
      }
      else -> true
    }

  private fun validateDeclaredType(node: PklDeclaredType, diagnosticsHolder: DiagnosticsHolder) {
    if (node.typeArgumentList?.types.isNullOrEmpty()) return

    val type = node.toType(project.pklBaseModule, emptyMap(), node.containingFile.pklProject)
    val argCount = node.typeArgumentList!!.types.size
    val paramCount =
      when (type) {
        is Type.Class -> type.typeArguments.size
        is Type.Alias -> type.typeArguments.size
        else -> 0
      }
    if (paramCount != 0 && paramCount != argCount) {
      diagnosticsHolder.addError(
        node,
        ErrorMessages.create("incorrectTypeArgumentCount", paramCount, argCount),
      )
      return
    }

    val unaliased = type.unaliased(project.pklBaseModule, node.containingFile.pklProject)
    if (
      unaliased is Type.Reference &&
        type.containsConstrainedType(project.pklBaseModule, node.containingFile.pklProject)
    ) {
      diagnosticsHolder.addError(node, ErrorMessages.create("invalidReferenceTypeWithConstraint"))
    }
  }

  private fun Type.containsConstrainedType(base: PklBaseModule, context: PklProject?): Boolean =
    !constraints.isEmpty() ||
      when (this) {
        is Type.Class -> typeArguments.any { it.containsConstrainedType(base, context) }
        is Type.Alias ->
          typeArguments.any { it.containsConstrainedType(base, context) } ||
            aliasedType(base, context).containsConstrainedType(base, context)
        is Type.Union ->
          leftType.containsConstrainedType(base, context) ||
            rightType.containsConstrainedType(base, context)
        else -> false
      }

  private fun validateModuleType(node: PklModuleType, diagnosticsHolder: DiagnosticsHolder) {
    // allowed in annotations
    if (node.parentOfType<PklAnnotation>() != null) return
    if (node.parentOfType<PklTypeAlias>() != null) {
      // not allowed in typealias bodies
      diagnosticsHolder.addWarning(
        node,
        ErrorMessages.create("invalidSelfTypeUsage", "module", "type alias") +
          " This will be an error in a future release.",
      )
    } else if (node.parentOfType<PklClassBody>() != null) {
      // not allowed in class bodies
      diagnosticsHolder.addWarning(
        node,
        ErrorMessages.create("invalidSelfTypeUsage", "module", "class") +
          " This will be an error in a future release.",
      )
    }
  }

  private fun validateThisType(node: PklThisType, diagnosticsHolder: DiagnosticsHolder) {
    // allowed in annotations
    if (node.parentOfType<PklAnnotation>() != null) return
    if (node.parentOfType<PklTypeAlias>() != null) {
      // not allowed in typealias bodies
      diagnosticsHolder.addError(
        node,
        ErrorMessages.create("invalidSelfTypeUsage", "this", "type alias"),
      )
    }
  }
}
