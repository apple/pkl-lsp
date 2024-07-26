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
package org.pkl.lsp.features

import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.PklLSPServer
import org.pkl.lsp.ast.*
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.computeThisType

abstract class Feature(protected open val server: PklLSPServer) {
  protected fun resolveQualifiedAccess(node: PklQualifiedAccessExpr): Node? {
    val base = PklBaseModule.instance
    val visitor = ResolveVisitors.firstElementNamed(node.memberNameText, base)
    // TODO: check if receiver is `module`
    return node.resolve(base, null, mapOf(), visitor)
  }

  protected fun resolveUnqualifiedAccess(node: PklUnqualifiedAccessExpr): Node? {
    val base = PklBaseModule.instance
    val visitor = ResolveVisitors.firstElementNamed(node.memberNameText, base)
    return node.resolve(base, null, mapOf(), visitor)
  }

  protected fun resolveSuperAccess(node: PklSuperAccessExpr): Node? {
    val base = PklBaseModule.instance
    val visitor = ResolveVisitors.firstElementNamed(node.memberNameText, base)
    return node.resolve(base, null, mapOf(), visitor)
  }

  protected fun resolveProperty(prop: PklProperty): Node? {
    val base = PklBaseModule.instance
    val name = prop.name
    val visitor = ResolveVisitors.firstElementNamed(name, base)
    return when {
      prop.type != null -> prop
      prop.isLocal -> prop
      else -> {
        val receiverType = prop.computeThisType(base, mapOf())
        Resolvers.resolveQualifiedAccess(receiverType, true, base, visitor)
      }
    }
  }
}