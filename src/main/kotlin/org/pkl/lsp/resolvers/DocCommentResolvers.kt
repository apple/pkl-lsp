/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.resolvers

import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.unescapedIdentifier

object DocCommentResolvers {
  fun resolveLink(
    project: Project,
    link: String,
    position: PklNode,
    resolveImports: Boolean,
  ): PklNode? {
    val context = position.containingFile.pklProject
    return when {
      link.contains('.') -> resolveQualifiedLink(project, link, position, resolveImports, context)
      else -> resolveUnqualifiedLink(project, link, position, resolveImports, context)
    }
  }

  private fun resolveQualifiedLink(
    project: Project,
    link: String,
    position: PklNode,
    resolveImports: Boolean,
    context: PklProject?,
  ): PklNode? {
    val parts = link.split('.')
    val base = project.pklBaseModule
    val propertyOrMethod = parts.last()
    val isProperty = !propertyOrMethod.endsWith("()")
    val memberName =
      unescapedIdentifier(if (isProperty) propertyOrMethod else propertyOrMethod.dropLast(2))
    val visitor = ResolveVisitors.firstElementNamed(memberName, base, resolveImports)
    Resolvers.resolveQualifiedDocCommentMemberLink(
      link,
      position,
      isProperty,
      base,
      visitor,
      context,
    )
    return visitor.result
  }

  private fun resolveUnqualifiedLink(
    project: Project,
    link: String,
    position: PklNode,
    resolveImports: Boolean,
    context: PklProject?,
  ): PklNode? {
    val isProperty = !link.endsWith("()")
    val memberName = unescapedIdentifier(if (isProperty) link else link.dropLast(2))
    val base = project.pklBaseModule
    val visitor = ResolveVisitors.firstElementNamed(memberName, base, resolveImports)
    return Resolvers.resolveUnqualifiedAccess(
      position,
      null,
      isProperty,
      base,
      mapOf(),
      visitor,
      context,
    )
      // search for type in supermodules
      ?: if (isProperty)
        Resolvers.resolveUnqualifiedTypeName(position, base, mapOf(), visitor, context)
      else null
  }
}
