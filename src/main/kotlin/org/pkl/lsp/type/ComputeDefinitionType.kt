/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.util.RecursionManager

fun PklNode?.computeResolvedImportType(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  context: PklProject?,
  preserveUnboundTypeVars: Boolean = false,
  canInferExprBody: Boolean = true,
): Type {
  if (this == null) return Type.Unknown

  return RecursionManager.doPreventingRecursion(this, "computeResolvedImportType", Type.Unknown) {
    when (this) {
      is PklModule -> Type.module(this, shortDisplayName, context)
      is PklClass -> Type.Class(this)
      is PklTypeAlias -> Type.alias(this, context)
      is PklMethod ->
        when {
          methodHeader.returnType != null ->
            methodHeader.returnType.toType(base, bindings, context, preserveUnboundTypeVars)
          else ->
            when {
              canInferExprBody && !isOverridable -> body.computeExprType(base, bindings, context)
              else -> Type.Unknown
            }
        }
      is PklProperty ->
        when {
          type != null -> type.toType(base, bindings, context, preserveUnboundTypeVars)
          else ->
            when {
              canInferExprBody && isLocalOrConstOrFixed ->
                expr.computeExprType(base, bindings, context)
              isDefinition(context) -> Type.Unknown
              else -> {
                val receiverType = computeThisType(base, bindings, context)
                val visitor =
                  ResolveVisitors.typeOfFirstElementNamed(
                    name,
                    null,
                    base,
                    false,
                    preserveUnboundTypeVars,
                  )
                Resolvers.resolveQualifiedAccess(receiverType, true, base, visitor, context)
              }
            }
        }
      is PklMemberPredicate -> {
        val receiverClassType =
          computeThisType(base, bindings, context).toClassType(base, context)
            ?: return@doPreventingRecursion Type.Unknown
        val baseType =
          when {
            receiverClassType.classEquals(base.listingType) -> receiverClassType.typeArguments[0]
            receiverClassType.classEquals(base.mappingType) -> receiverClassType.typeArguments[1]
            else -> Type.Unknown
          }
        // flow typing support, e.g. `[[this is Person]] { ... }`
        val cond = conditionExpr ?: return@doPreventingRecursion baseType
        val visitor =
          ResolveVisitors.typeOfFirstElementNamed(
            "this",
            null,
            base,
            isNullSafeAccess = false,
            preserveUnboundTypeVars = false,
          )
        if (
          Resolvers.visitSatisfiedCondition(cond, bindings, visitor, context) ||
            visitor.result == Type.Unknown
        )
          baseType
        else visitor.result
      }
      is PklObjectEntry,
      is PklObjectElement -> {
        val receiverClassType =
          computeThisType(base, bindings, context).toClassType(base, context)
            ?: return@doPreventingRecursion Type.Unknown
        when {
          receiverClassType.classEquals(base.listingType) -> receiverClassType.typeArguments[0]
          receiverClassType.classEquals(base.mappingType) -> receiverClassType.typeArguments[1]
          else -> Type.Unknown
        }
      }
      is PklTypedIdentifier ->
        when {
          type != null -> type.toType(base, bindings, context, preserveUnboundTypeVars)
          else -> { // try to infer identifier type
            when (val identifierOwner = parent) {
              is PklLetExpr -> identifierOwner.varExpr.computeExprType(base, bindings, context)
              is PklForGenerator -> {
                val iterableType =
                  identifierOwner.iterableExpr.computeExprType(base, bindings, context)
                val iterableClassType =
                  iterableType.toClassType(base, context)
                    ?: return@doPreventingRecursion Type.Unknown
                val keyValueVars = identifierOwner.parameters
                when {
                  keyValueVars.size > 1 && keyValueVars[0] == this -> {
                    when {
                      iterableClassType.classEquals(base.intSeqType) -> base.intType
                      iterableClassType.classEquals(base.listType) ||
                        iterableClassType.classEquals(base.setType) ||
                        iterableClassType.classEquals(base.listingType) -> base.intType
                      iterableClassType.classEquals(base.mapType) ||
                        iterableClassType.classEquals(base.mappingType) ->
                        iterableClassType.typeArguments[0]
                      iterableClassType.isSubtypeOf(base.typedType, base, context) ->
                        base.stringType
                      else -> Type.Unknown
                    }
                  }
                  else -> {
                    when {
                      iterableClassType.classEquals(base.intSeqType) -> base.intType
                      iterableClassType.classEquals(base.listType) ||
                        iterableClassType.classEquals(base.setType) ||
                        iterableClassType.classEquals(base.listingType) ->
                        iterableClassType.typeArguments[0]
                      iterableClassType.classEquals(base.mapType) ||
                        iterableClassType.classEquals(base.mappingType) ->
                        iterableClassType.typeArguments[1]
                      iterableClassType.isSubtypeOf(base.typedType, base, context) ->
                        Type.Unknown // could strengthen value type to union of property types
                      else -> Type.Unknown
                    }
                  }
                }
              }
              is PklParameterList -> {
                when (val parameterListOwner = identifierOwner.parent) {
                  is PklFunctionLiteralExpr -> {
                    val functionType =
                      parameterListOwner.inferExprTypeFromContext(base, bindings, context)
                    getFunctionParameterType(this, identifierOwner, functionType, base, context)
                  }
                  is PklObjectBody ->
                    when (val objectBodyOwner = parameterListOwner.parent) {
                      is PklExpr -> {
                        val functionType = objectBodyOwner.computeExprType(base, bindings, context)
                        getFunctionParameterType(this, identifierOwner, functionType, base, context)
                      }
                      is PklObjectBodyOwner -> {
                        @Suppress("BooleanLiteralArgument")
                        val functionType =
                          objectBodyOwner.computeResolvedImportType(
                            base,
                            bindings,
                            context,
                            canInferExprBody = false,
                          )
                        getFunctionParameterType(this, identifierOwner, functionType, base, context)
                      }
                      else -> Type.Unknown
                    }
                  else -> Type.Unknown
                }
              }
              else -> Type.Unknown
            }
          }
        }
      is PklTypeParameter -> Type.Unknown
      else -> Type.Unknown
    }
  }
}

private fun getFunctionParameterType(
  parameter: PklTypedIdentifier,
  parameterList: PklParameterList,
  functionType: Type,
  base: PklBaseModule,
  context: PklProject?,
): Type {

  return when (functionType) {
    // e.g., `(String) -> Int` or `Function1<String, Int>`
    is Type.Class -> {
      when {
        functionType.isFunctionType -> {
          val typedIdents = parameterList.elements
          val paramIndex = typedIdents.indexOf(parameter)
          val typeArguments = functionType.typeArguments
          if (paramIndex >= typeArguments.lastIndex) Type.Unknown else typeArguments[paramIndex]
        }
        else -> Type.Unknown
      }
    }
    is Type.Alias ->
      getFunctionParameterType(
        parameter,
        parameterList,
        functionType.unaliased(base, context),
        base,
        context,
      )
    else -> Type.Unknown
  }
}
