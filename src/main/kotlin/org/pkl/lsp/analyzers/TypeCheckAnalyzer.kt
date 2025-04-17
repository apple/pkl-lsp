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
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklExpr
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.type.ConstraintValue
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.computeExprType
import org.pkl.lsp.type.inferExprTypeFromContext
import org.pkl.lsp.type.toConstraintExpr

class TypeCheckAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, holder: DiagnosticsHolder): Boolean {
    if (node !is PklExpr) return true

    val module = node.enclosingModule ?: return false
    val project = module.project
    val base = project.pklBaseModule
    val context = node.containingFile.pklProject

    val expectedType = node.inferExprTypeFromContext(base, mapOf(), context)
    if (expectedType == Type.Unknown) return true

    val exprType = node.computeExprType(base, mapOf(), context)
    val exprValue = lazy { node.toConstraintExpr(base, context).evaluate(ConstraintValue.Error) }
    val failedConstraints = mutableListOf<Pair<Type, Int>>()
    if (!isTypeMatch(exprType, exprValue, expectedType, failedConstraints, base, context)) {
      when {
        failedConstraints.isEmpty() ->
          reportTypeMismatch(node, exprType, expectedType, base, holder, context)
        else -> reportConstraintMismatch(node, exprValue, failedConstraints, holder)
      }
    }
    return true
  }

  /**
   * Type and constraint checking of union types needs to be intertwined. Otherwise, if the left
   * alternative of a union type had (only) a type mismatch, and the right alternative (only) a
   * constraint mismatch, the overall check would still succeed.
   */
  private fun isTypeMatch(
    exprType: Type,
    exprValue: Lazy<ConstraintValue>,
    memberType: Type,
    failedConstraints: MutableList<Pair<Type, Int>>,
    base: PklBaseModule,
    context: PklProject?,
  ): Boolean {

    return when {
      exprType is Type.Alias -> {
        isTypeMatch(
          exprType.aliasedType(base, context),
          exprValue,
          memberType,
          failedConstraints,
          base,
          context,
        )
      }
      exprType is Type.Union -> {
        // This can cause multiple checks of the same top-level or nested constraint.
        // To avoid this, constraint check results could be cached while this method runs.
        isTypeMatch(exprType.leftType, exprValue, memberType, failedConstraints, base, context) &&
          isTypeMatch(exprType.rightType, exprValue, memberType, failedConstraints, base, context)
      }
      memberType is Type.Alias -> {
        isTypeMatch(
          exprType,
          exprValue,
          memberType.aliasedType(base, context),
          failedConstraints,
          base,
          context,
        ) && isConstraintMatch(exprValue, memberType, failedConstraints, true)
      }
      memberType is Type.Union && !memberType.isUnionOfStringLiterals -> {
        (isTypeMatch(exprType, exprValue, memberType.leftType, failedConstraints, base, context) ||
          isTypeMatch(
            exprType,
            exprValue,
            memberType.rightType,
            failedConstraints,
            base,
            context,
          )) && isConstraintMatch(exprValue, memberType, failedConstraints, true)
      }
      else -> {
        exprType.isSubtypeOf(memberType, base, context) &&
          isConstraintMatch(exprValue, memberType, failedConstraints, false)
      }
    }
  }

  private fun isConstraintMatch(
    exprValue: Lazy<ConstraintValue>,
    memberType: Type,
    failedConstraints: MutableList<Pair<Type, Int>>,
    isOverride: Boolean,
  ): Boolean {

    var index = 0
    var failedIndex: Int = -1
    for (constraint in memberType.constraints) {
      if (constraint.evaluate(exprValue.value) == ConstraintValue.False) {
        failedIndex = index
        break
      }
      index += 1
    }

    if (failedIndex == -1) return true

    if (isOverride) failedConstraints.clear()
    failedConstraints.add(memberType to failedIndex)
    return false
  }

  private fun reportTypeMismatch(
    expr: PklExpr,
    actualType: Type,
    requiredType: Type,
    base: PklBaseModule,
    holder: DiagnosticsHolder,
    context: PklProject?,
  ) {
    when {
      !actualType.hasCommonSubtypeWith(requiredType, base, context) -> {
        // no subtype of actual type is a subtype of required type ->
        // cannot be caused by the type system being too weak ->
        // runtime type check cannot succeed ->
        // report error
        holder.addError(expr, typeMismatchMessage("Type", requiredType, actualType)) {
          problemGroup = PklProblemGroups.typeMismatch
        }
      }
      actualType.isNullable(base, context) &&
        actualType.nonNull(base, context).isSubtypeOf(requiredType, base, context) -> {
        // actual type is only too weak in that it admits `null` ->
        // could be caused by the type system being too weak ->
        // runtime type check could succeed ->
        // report warning with custom message
        holder.addWarning(expr, typeMismatchMessage("Nullability", requiredType, actualType)) {
          problemGroup = PklProblemGroups.typeMismatch
        }
      }
      else -> {
        // actual type is too weak ->
        // could be caused by the type system being too weak ->
        // runtime type check could succeed ->
        // report warning
        holder.addWarning(expr, typeMismatchMessage("Type", requiredType, actualType)) {
          problemGroup = PklProblemGroups.typeMismatch
        }
      }
    }
  }

  private fun typeMismatchMessage(header: String, expectedType: Type, actualType: Type): String {
    return ErrorMessages.create(
      "typeOrConstraintMismatch",
      header,
      expectedType.render(),
      actualType.render(),
    )
  }

  private fun reportConstraintMismatch(
    expr: PklExpr,
    exprValue: Lazy<ConstraintValue>,
    constraints: List<Pair<Type, Int>>,
    holder: DiagnosticsHolder,
  ) {
    var renderedConstraint = buildString {
      var isFirst = true
      for ((type, index) in constraints) {
        if (isFirst) {
          isFirst = false
        } else {
          append(" || ")
        }
        val constraint = type.constraints[index]
        append(constraint.render())
      }
    }
    val message =
      ErrorMessages.create("constraintViolation", renderedConstraint, exprValue.value.render())
    holder.addError(expr, message) { problemGroup = PklProblemGroups.typeMismatch }
  }
}
