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

import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.ast.PklDocComment
import org.pkl.lsp.ast.PklImport
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklModuleMember
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklObjectMethod
import org.pkl.lsp.ast.PklObjectProperty
import org.pkl.lsp.ast.PklTypeName
import org.pkl.lsp.ast.PklTypeParameter
import org.pkl.lsp.ast.PklTypedIdentifier
import org.pkl.lsp.ast.PklUnqualifiedAccessExpr
import org.pkl.lsp.ast.memberName

fun visitLocalDefinitions(module: PklModule, visitor: (PklNode) -> Unit) {
  module.accept(
    object : PklRecursiveVisitor<Unit>() {
      override fun visitImport(node: PklImport) {
        super.visitImport(node)
        visitor(node)
      }

      override fun visitModuleMember(node: PklModuleMember) {
        super.visitModuleMember(node)
        if (node.isLocal) visitor(node)
      }

      override fun visitObjectProperty(node: PklObjectProperty) {
        super.visitObjectProperty(node)
        if (node.isLocal) visitor(node)
      }

      override fun visitObjectMethod(node: PklObjectMethod) {
        super.visitObjectMethod(node)
        if (node.isLocal) visitor(node)
      }

      override fun visitTypedIdentifier(node: PklTypedIdentifier) {
        super.visitTypedIdentifier(node)
        visitor(node)
      }

      override fun visitTypeParameter(node: PklTypeParameter) {
        visitor(node)
      }
    }
  )
}

// TODO: honor imports used by `Deprecated.replaceWith`
fun visitUsedLocalDefinitions(module: PklModule, base: PklBaseModule, visitor: (PklNode) -> Unit) {
  val context = module.containingFile.pklProject
  module.accept(
    object : PklRecursiveVisitor<Unit>() {
      override fun visitUnqualifiedAccessExpr(node: PklUnqualifiedAccessExpr) {
        super.visitUnqualifiedAccessExpr(node)
        val resolveVisitor =
          ResolveVisitors.firstElementNamed(node.memberNameText, base, resolveImports = false)
        node.resolve(base, null, mapOf(), resolveVisitor, context)?.let { visitor(it) }
      }

      override fun visitTypeName(node: PklTypeName) {
        when (val moduleName = node.moduleName?.identifier?.text) {
          null -> {
            val simpleName = node.simpleTypeName.identifier?.text ?: return
            val resolveVisitor =
              ResolveVisitors.firstElementNamed(simpleName, base, resolveImports = false)
            Resolvers.resolveUnqualifiedTypeName(node, base, mapOf(), resolveVisitor, context)
              ?.let { visitor(it) }
          }
          else -> {
            module.imports.find { it.memberName == moduleName }?.let { visitor(it) }
          }
        }
      }

      override fun visitDocComment(node: PklDocComment) {
        // assertion: references are always in order, and `distinctBy` will always give us the first
        // reference of a link
        // (e.g. `foo` within `foo.bar.baz`)
        for (reference in node.references.distinctBy { it.fullText }) {
          DocCommentResolvers.resolveLink(
              module.project,
              reference.link,
              node,
              resolveImports = false,
            )
            ?.let { visitor(it) }
        }
      }
    }
  )
}
