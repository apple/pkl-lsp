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

import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.computeExprType
import org.pkl.lsp.type.toType

// all expression analysis that's not related to type-checking, or visibility
class ExprAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, diagnosticsHolder: DiagnosticsHolder): Boolean {
    val base = project.pklBaseModule
    val context = node.containingFile.pklProject
    when (node) {
      is PklTypeTestExpr -> {
        val exprType = node.expr.computeExprType(base, mapOf(), context)
        val testedType = node.type.toType(base, mapOf(), context)
        if (testedType.hasConstraints) return false
        if (exprType != Type.Unknown && exprType.isSubtypeOf(testedType, base, context)) {
          diagnosticsHolder.addWarning(node, ErrorMessages.create("expressionIsAlwaysTrue"))
        } else if (!testedType.isSubtypeOf(exprType, base, context)) {
          diagnosticsHolder.addWarning(node, ErrorMessages.create("expressionIsAlwaysFalse"))
        }
      }
      is PklTypeCastExpr -> {
        val exprType = node.expr.computeExprType(base, mapOf(), context)
        val testedType = node.type.toType(base, mapOf(), context)
        if (
          !testedType.hasConstraints &&
            exprType != Type.Unknown &&
            exprType.isSubtypeOf(testedType, base, context)
        ) {
          val message = ErrorMessages.create("typeCastIsRedundant")
          diagnosticsHolder.addDiagnostic(
            node,
            message,
            span = node.operator.span.endAt(node.type!!.span),
          ) {
            severity = DiagnosticSeverity.Hint
            tags = listOf(DiagnosticTag.Unnecessary)
          }
          // TODO add quick fixes
          //          if (holder.currentFile.canModify()) {
          //            annotation.withFix(PklRedundantTypeCastQuickFix(element))
          //          }
        } else if (
          !testedType.isSubtypeOf(exprType, base, context) &&
            !testedType.hasCommonSubtypeWith(exprType, base, context)
        ) {
          val message = ErrorMessages.create("typeCastCannotSucceed")
          diagnosticsHolder.addError(
            node,
            message,
            span = node.operator.span.endAt(node.type!!.span),
          )
        }
      }
      is PklNullCoalesceExpr -> {
        val leftType = node.leftExpr.computeExprType(base, mapOf(), context)
        if (!leftType.isNullable(base, context)) {
          val message = ErrorMessages.create("nullCoalescingIsRedundant")
          diagnosticsHolder.addDiagnostic(
            node,
            message,
            span = node.operator.span.endAt(node.rightExpr.span),
          ) {
            severity = DiagnosticSeverity.Hint
            tags = listOf(DiagnosticTag.Unnecessary)
          }
          // TODO add quick fixes
          //          if (holder.currentFile.canModify()) {
          //            annotation.withFix(PklRedundantNullCoalesceQuickFix(element))
          //          }
        }
      }
      is PklNonNullExpr -> {
        val type = node.expr.computeExprType(base, mapOf(), context)
        if (!type.isNullable(base, context)) {
          val message = ErrorMessages.create("nonNullIsRedundant")
          diagnosticsHolder.addDiagnostic(node.lastTerminalOfType(TokenType.NON_NULL)!!, message) {
            severity = DiagnosticSeverity.Hint
            tags = listOf(DiagnosticTag.Unnecessary)
          }
          // TODO add quick fixes
          //          if (holder.currentFile.canModify()) {
          //            annotation.withFix(PklRedundantNonNullAssertionQuickFix(element))
          //          }
        }
      }
      else -> return true
    }
    return false
  }
}
