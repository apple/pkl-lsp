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
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklAnnotation
import org.pkl.lsp.ast.PklClass
import org.pkl.lsp.ast.PklClassExtendsClause
import org.pkl.lsp.ast.PklConstrainedType
import org.pkl.lsp.ast.PklDeclaredType
import org.pkl.lsp.ast.PklMemberPredicate
import org.pkl.lsp.ast.PklMethod
import org.pkl.lsp.ast.PklModuleType
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklObjectBody
import org.pkl.lsp.ast.PklProperty
import org.pkl.lsp.ast.PklThisType
import org.pkl.lsp.ast.PklTypeAlias
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
    }
  }

  private fun validateModuleType(node: PklModuleType, diagnosticsHolder: DiagnosticsHolder) {
    var diagLocation: String? = null
    var parent = node.parent
    while (parent != null) {
      when {
        // allowed in class extends clause
        parent is PklClassExtendsClause -> break
        // not allowed in class bodies
        parent is PklClass -> {
          diagLocation = "within a class body"
          break
        }
        // not allowed in typealias bodies
        parent is PklTypeAlias -> {
          diagLocation = "within a type alias body"
          break
        }
        // not allowed in annotation bodies
        parent is PklAnnotation -> {
          diagLocation = "within an annotation body"
          break
        }
        // not allowed in const properties
        parent is PklProperty && parent.isConst ->
          diagLocation = "from const property `${parent.name}`"
        // not allowed in const methods
        parent is PklMethod && parent.isConst -> diagLocation = "from const method `${parent.name}`"
      }
      parent = parent.parent
    }

    if (diagLocation != null) {
      diagnosticsHolder.addWarning(
        node,
        ErrorMessages.create("invalidSelfTypeUsage", "module", diagLocation) +
          " This will be an error in a future release.",
      )
    }
  }

  private fun validateThisType(node: PklThisType, diagnosticsHolder: DiagnosticsHolder) {
    var prev: PklNode = node
    var parent = node.parent
    while (parent != null) {
      when {
        // always allowed in object bodies
        parent is PklObjectBody -> break
        // always allowed in custom this scopes
        parent is PklConstrainedType && parent.exprs.contains(prev) -> break
        parent is PklMemberPredicate && parent.conditionExpr == prev -> break
        // not allowed in type alias bodies
        parent is PklTypeAlias -> {
          diagnosticsHolder.addError(
            node,
            ErrorMessages.create("invalidSelfTypeUsage", "this", "within a type alias body"),
          )
          break
        }
      }
      prev = parent
      parent = parent.parent
    }
  }
}
