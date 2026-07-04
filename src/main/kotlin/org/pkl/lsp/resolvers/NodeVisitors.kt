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
      override fun visitImport(o: PklImport) {
        super.visitImport(o)
        visitor(o)
      }

      override fun visitModuleMember(o: PklModuleMember) {
        super.visitModuleMember(o)
        if (o.isLocal) visitor(o)
      }

      override fun visitObjectProperty(o: PklObjectProperty) {
        super.visitObjectProperty(o)
        if (o.isLocal) visitor(o)
      }

      override fun visitObjectMethod(o: PklObjectMethod) {
        super.visitObjectMethod(o)
        if (o.isLocal) visitor(o)
      }

      override fun visitTypedIdentifier(o: PklTypedIdentifier) {
        super.visitTypedIdentifier(o)
        visitor(o)
      }

      override fun visitTypeParameter(o: PklTypeParameter) {
        visitor(o)
      }
    }
  )
}

// TODO: honor imports used by `Deprecated.replaceWith`
fun visitUsedLocalDefinitions(module: PklModule, base: PklBaseModule, visitor: (PklNode) -> Unit) {
  val context = module.containingFile.pklProject
  module.accept(
    object : PklRecursiveVisitor<Unit>() {
      override fun visitUnqualifiedAccessExpr(expr: PklUnqualifiedAccessExpr) {
        super.visitUnqualifiedAccessExpr(expr)
        val resolveVisitor =
          ResolveVisitors.firstElementNamed(expr.memberNameText, base, resolveImports = false)
        expr.resolve(base, null, mapOf(), resolveVisitor, context)?.let { visitor(it) }
      }

      override fun visitTypeName(typeName: PklTypeName) {
        when (val moduleName = typeName.moduleName?.identifier?.text) {
          null -> {
            val simpleName = typeName.simpleTypeName.identifier?.text ?: return
            val resolveVisitor =
              ResolveVisitors.firstElementNamed(simpleName, base, resolveImports = false)
            Resolvers.resolveUnqualifiedTypeName(typeName, base, mapOf(), resolveVisitor, context)
              ?.let { visitor(it) }
          }
          else -> {
            module.imports.find { it.memberName == moduleName }?.let { visitor(it) }
          }
        }
      }

      //      override fun visitDocComment(o: PklDocComment) {
      //        for (reference in o.references.mapTo(mutableSetOf()) { it.fullText }) {
      //          // note: this is not necessarily a module name
      //          val simpleName = reference.split(".").first()
      //          module.imports.find { it.memberName == simpleName }?.let { visitor(it) }
      //        }
      //      }
    }
  )
}
