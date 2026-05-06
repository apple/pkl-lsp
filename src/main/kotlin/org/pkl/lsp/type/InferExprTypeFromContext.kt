/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.type

import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors

// TODO: Returns upper bounds for some binary expression operands,
//  but since this isn't communicated to the caller,
//  ExprAnnotator doesn't report warning on successful type check.
//  Example: `Duration|DataSize + Duration|DataSize` type checks w/o warning.
fun PklExpr?.inferExprTypeFromContext(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  context: PklProject?,
  resolveTypeParamsInParamTypes: Boolean = true,
  canInferParentExpr: Boolean = true,
): Type =
  when {
    this == null -> Type.Unknown
    // TODO: cache the results
    // bindings.isEmpty() && resolveTypeParamsInParamTypes && canInferParentExpr -> ...
    else ->
      doInferExprTypeFromContext(
        base,
        bindings,
        parent,
        context,
        resolveTypeParamsInParamTypes,
        canInferParentExpr,
      )
  }

private fun PklExpr?.doInferExprTypeFromContext(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  parent: PklNode?,
  context: PklProject?,
  resolveTypeParamsInParamTypes: Boolean = true,
  canInferParentExpr: Boolean = true,
): Type {
  if (this == null || parent == null) return Type.Unknown

  val expr = this

  val result =
    parent.accept(
      object : PklVisitor<Type>() {
        override fun visitExpr(node: PklExpr): Type = Type.Unknown

        override fun visitObjectEntry(node: PklObjectEntry): Type {
          return when (expr) {
            node.keyExpr -> {
              val enclosingObjectType =
                node.computeThisType(base, bindings, context).toClassType(base, context)
                  ?: return Type.Unknown
              when {
                enclosingObjectType.classEquals(base.listingType) -> base.intType
                enclosingObjectType.classEquals(base.mappingType) ->
                  enclosingObjectType.typeArguments[0]
                else -> Type.Unknown
              }
            }
            node.valueExpr -> {
              val defaultExpectedType by lazy {
                node.computeResolvedImportType(base, bindings, context, canInferExprBody = false)
              }
              val resolvedKeyClass by lazy {
                val keyExpr = (node.keyExpr as? PklUnqualifiedAccessExpr) ?: return@lazy null
                val visitor = ResolveVisitors.firstElementNamed(keyExpr.memberNameText, base, true)
                keyExpr.resolve(base, null, bindings, visitor, context) as? PklClass
              }
              // special support for converters/convertPropertyTransformers
              if (
                // optimization: only compute type if within a property called "converters" or
                // "convertPropertyTransformers" in a BaseValueRenderer subclass
                node.keyExpr
                  ?.computeThisType(base, bindings, context)
                  ?.isSubtypeOf(base.baseValueRenderer, base, context) == true
              ) {
                when (expr.parentOfType<PklProperty>()?.name) {
                  "converters" ->
                    resolvedKeyClass?.let {
                      base.function1Type.withTypeArguments(Type.Class(it), base.anyType)
                    } ?: defaultExpectedType
                  "convertPropertyTransformers" ->
                    resolvedKeyClass?.let {
                      base.function1Type.withTypeArguments(Type.Class(it), Type.Class(it))
                    } ?: defaultExpectedType
                  else -> defaultExpectedType
                }
              } else {
                defaultExpectedType
              }
            }
            else -> Type.Unknown
          }
        }

        override fun visitObjectSpread(node: PklObjectSpread): Type {
          val underlyingType =
            when (expr) {
              node.expr -> {
                val enclosingObjectType =
                  node.computeThisType(base, bindings, context).toClassType(base, context)
                if (enclosingObjectType == null) base.iterableType
                else base.spreadType(enclosingObjectType)
              }
              else -> base.iterableType
            }
          return if (node.isNullable) underlyingType.nullable(base, context) else underlyingType
        }

        override fun visitMemberPredicate(node: PklMemberPredicate): Type =
          when (expr) {
            node.conditionExpr -> base.booleanType
            node.valueExpr ->
              node.computeResolvedImportType(base, bindings, context, canInferExprBody = false)
            else -> Type.Unknown // parse error
          }

        override fun visitWhenGenerator(node: PklWhenGenerator): Type =
          when (expr) {
            node.conditionExpr -> base.booleanType
            else -> Type.Unknown // parse error
          }

        override fun visitForGenerator(node: PklForGenerator): Type =
          when (expr) {
            node.iterableExpr -> base.iterableType
            else -> Type.Unknown // parse error
          }

        override fun visitArgumentList(node: PklArgumentList): Type {
          val argIndex = node.elements.indexOfFirst { it === expr }
          if (argIndex == -1) return Type.Unknown

          val accessExpr = node.parent as PklAccessExpr
          val visitor =
            ResolveVisitors.paramTypesOfFirstMethodNamed(
              accessExpr.memberNameText,
              base,
              resolveTypeParamsInParamTypes,
            )
          val paramTypes = accessExpr.resolve(base, null, bindings, visitor, context)
          if (paramTypes.isNullOrEmpty()) return Type.Unknown

          base.varArgsType.let { varArgsType ->
            if (argIndex >= paramTypes.lastIndex) {
              val lastParamType = paramTypes.last()
              if (lastParamType is Type.Class && lastParamType.classEquals(varArgsType)) {
                return lastParamType.typeArguments[0]
              }
            }
          }

          return paramTypes.getOrNull(argIndex) ?: Type.Unknown
        }

        override fun visitSubscriptExpr(node: PklSubscriptExpr): Type {
          return when (expr) {
            node.leftExpr -> base.subscriptableType
            else -> {
              doVisitSubscriptExpr(node.leftExpr.computeExprType(base, bindings, context))
            }
          }
        }

        // computes the type of `y` in `x[y]` given the type of `x`
        private fun doVisitSubscriptExpr(subscriptableType: Type): Type {
          return when (val unaliasedType = subscriptableType.unaliased(base, context)) {
            base.stringType -> base.intType
            base.dynamicType -> Type.Unknown
            is Type.Class -> {
              when {
                unaliasedType.classEquals(base.listType) -> base.intType
                unaliasedType.classEquals(base.setType) -> base.intType
                unaliasedType.classEquals(base.mapType) -> unaliasedType.typeArguments[0]
                unaliasedType.classEquals(base.listingType) -> base.intType
                unaliasedType.classEquals(base.mappingType) -> unaliasedType.typeArguments[0]
                base.bytesType != null && unaliasedType.classEquals(base.bytesType) -> base.intType
                else -> Type.Unknown // unsupported type
              }
            }
            is Type.Union ->
              Type.union(
                doVisitSubscriptExpr(unaliasedType.leftType),
                doVisitSubscriptExpr(unaliasedType.rightType),
                base,
                context,
              )
            else -> Type.Unknown // unsupported type
          }
        }

        override fun visitExponentiationExpr(node: PklExponentiationExpr): Type {
          return when (expr) {
            node.leftExpr ->
              Type.union(base.numberType, base.dataSizeType, base.durationType, base, context)
            else -> base.numberType
          }
        }

        override fun visitMultiplicativeExpr(node: PklMultiplicativeExpr): Type {
          return doVisitMultiplicativeBinExpr(
            node.otherExpr(expr).computeExprType(base, bindings, context)
          )
        }

        private fun doVisitMultiplicativeBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base, context)) {
            base.durationType -> Type.union(base.numberType, base.durationType, base, context)
            base.dataSizeType -> Type.union(base.numberType, base.dataSizeType, base, context)
            is Type.Union ->
              Type.union(
                doVisitMultiplicativeBinExpr(unaliasedType.leftType),
                doVisitMultiplicativeBinExpr(unaliasedType.rightType),
                base,
                context,
              )
            // int/float/number/unsupported type
            else -> base.multiplicativeOperandType
          }
        }

        override fun visitAdditiveExpr(node: PklAdditiveExpr): Type {
          return doVisitAdditiveBinExpr(
            node.otherExpr(expr).computeExprType(base, bindings, context)
          )
        }

        private fun doVisitAdditiveBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base, context)) {
            base.stringType -> base.stringType
            base.intType,
            base.floatType,
            base.numberType -> base.numberType
            base.durationType -> base.durationType
            base.dataSizeType -> base.dataSizeType
            base.bytesType ->
              base.bytesType
                ?:
                // if we fall through, both bytesType and unaliasedType is [null] (possible when Pkl
                // < 0.29)
                base.additiveOperandType
            is Type.Class ->
              when {
                unaliasedType.classEquals(base.listType) ||
                  unaliasedType.classEquals(base.setType) ||
                  unaliasedType.classEquals(base.collectionType) -> base.collectionType
                unaliasedType.classEquals(base.mapType) -> base.mapType
                // unsupported type
                else -> base.additiveOperandType
              }
            is Type.Union ->
              Type.union(
                doVisitAdditiveBinExpr(unaliasedType.leftType),
                doVisitAdditiveBinExpr(unaliasedType.rightType),
                base,
                context,
              )
            // unsupported type
            else -> base.additiveOperandType
          }
        }

        override fun visitComparisonExpr(node: PklComparisonExpr): Type {
          return doVisitComparisonBinExpr(
            node.otherExpr(expr).computeExprType(base, bindings, context)
          )
        }

        private fun doVisitComparisonBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base, context)) {
            base.stringType -> base.stringType
            base.intType,
            base.floatType,
            base.numberType -> base.numberType
            base.durationType -> base.durationType
            base.dataSizeType -> base.dataSizeType
            is Type.Union ->
              Type.union(
                doVisitComparisonBinExpr(unaliasedType.leftType),
                doVisitComparisonBinExpr(unaliasedType.rightType),
                base,
                context,
              )
            else -> base.comparableType // unsupported type
          }
        }

        override fun visitLogicalAndExpr(node: PklLogicalAndExpr): Type = base.booleanType

        override fun visitPipeExpr(node: PklPipeExpr): Type =
          when (expr) {
            node.rightExpr -> {
              val paramType = node.leftExpr.computeExprType(base, mapOf(), context)
              val returnType = inferParentExpr(node)
              Type.function1(paramType, returnType, base)
            }
            node.leftExpr -> doVisitPipeExpr(node.rightExpr.computeExprType(base, mapOf(), context))
            else -> Type.Unknown // parse error
          }

        private fun doVisitPipeExpr(rightExprType: Type): Type {
          return when (val unaliasedType = rightExprType.unaliased(base, context)) {
            is Type.Class ->
              when {
                unaliasedType.classEquals(base.function1Type) -> unaliasedType.typeArguments[0]
                else -> Type.Unknown // unsupported type
              }
            is Type.Union ->
              Type.union(
                doVisitPipeExpr(unaliasedType.leftType),
                doVisitPipeExpr(unaliasedType.rightType),
                base,
                context,
              )
            else -> Type.Unknown // unsupported type
          }
        }

        override fun visitIfExpr(node: PklIfExpr): Type =
          when (expr) {
            node.conditionExpr -> base.booleanType
            node.thenExpr,
            node.elseExpr -> inferParentExpr(node)
            else -> Type.Unknown
          }

        override fun visitLetExpr(node: PklLetExpr): Type =
          when (expr) {
            node.varExpr -> node.parameter.type.toType(base, bindings, context)
            node.bodyExpr -> inferParentExpr(node)
            else -> Type.Unknown
          }

        override fun visitParenthesizedExpr(node: PklParenthesizedExpr): Type {
          return doInferExprTypeFromContext(
            base,
            bindings,
            node.parent,
            context,
            resolveTypeParamsInParamTypes,
            canInferParentExpr,
          )
        }

        override fun visitReadExpr(node: PklReadExpr): Type = base.stringType

        override fun visitThrowExpr(node: PklThrowExpr): Type = base.stringType

        override fun visitUnaryMinusExpr(node: PklUnaryMinusExpr): Type =
          base.multiplicativeOperandType

        override fun visitLogicalNotExpr(node: PklLogicalNotExpr): Type = base.booleanType

        private fun inferParentExpr(parent: PklExpr) =
          when {
            canInferParentExpr ->
              parent.doInferExprTypeFromContext(
                base,
                bindings,
                parent.parent,
                context,
                resolveTypeParamsInParamTypes,
                true,
              )
            else -> Type.Unknown
          }
      }
    )

  return result
    ?: parent.computeResolvedImportType(base, bindings, context, canInferExprBody = false)
}
