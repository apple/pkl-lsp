/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
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

fun PklNode.computeThisType(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  context: PklProject?,
): Type {
  var element: PklNode? = this
  var memberPredicateExprSeen = false
  var objectBodySeen = false
  var skipNextObjectBody = false

  while (element != null) {
    when (element) {
      is PklAmendExpr,
      is PklNewExpr -> {
        if (objectBodySeen) {
          val type = element.computeExprType(base, bindings, context).amending(base, context)
          return when {
            memberPredicateExprSeen -> {
              val classType = type.toClassType(base, context) ?: return Type.Unknown
              when {
                classType.classEquals(base.listingType) -> classType.typeArguments[0]
                classType.classEquals(base.mappingType) -> classType.typeArguments[1]
                else -> Type.Unknown
              }
            }
            else -> type
          }
        }
      }
      is PklExpr -> {
        val parent = element.parent
        if (
          parent is PklWhenGenerator && element === parent.conditionExpr ||
            parent is PklForGenerator && element === parent.iterableExpr ||
            parent is PklObjectEntry && element === parent.keyExpr
        ) {
          skipNextObjectBody = true
        } else if (parent is PklMemberPredicate && element === parent.conditionExpr) {
          memberPredicateExprSeen = true
        }
      }
      is PklObjectBody ->
        when {
          skipNextObjectBody -> skipNextObjectBody = false
          else -> objectBodySeen = true
        }
      is PklProperty,
      is PklObjectElement,
      is PklObjectEntry,
      is PklMemberPredicate -> {
        if (objectBodySeen) {
          val type =
            element.computeResolvedImportType(base, bindings, context).amending(base, context)
          return when {
            memberPredicateExprSeen -> {
              val classType = type.toClassType(base, context) ?: return Type.Unknown
              when {
                classType.classEquals(base.listingType) -> classType.typeArguments[0]
                classType.classEquals(base.mappingType) -> classType.typeArguments[1]
                else -> Type.Unknown
              }
            }
            else -> type
          }
        }
      }
      is PklConstrainedType -> return element.type.toType(base, bindings, context)
      is PklModule,
      is PklClass,
      is PklTypeAlias -> return element.computeResolvedImportType(base, bindings, context)
      is PklAnnotation ->
        return element.typeName?.resolve(context).computeResolvedImportType(base, bindings, context)
    }
    element = element.parent
  }

  return Type.Unknown
}
