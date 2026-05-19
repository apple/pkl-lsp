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
package org.pkl.lsp.ast

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.Project
import org.pkl.lsp.type.Type

interface PklReferenceQualifiedAccessProxy : PklNode {
  val name: String
  val domain: Type
  val referent: PklType
  val type: PklType

  fun toCompletionItem(): CompletionItem {
    return CompletionItem(name).apply {
      kind = CompletionItemKind.Field
      detail = type.render()
    }
  }
}

class PklReferenceQualifiedAccessProxyImpl(
  project: Project,
  override val name: String,
  override val domain: Type,
  override val referent: PklType,
) : FakePklNode(project), PklReferenceQualifiedAccessProxy {
  override fun <R> accept(visitor: PklVisitor<R>): R? = null

  override val type: PklType =
    DeclaredType(
      project,
      project.pklRefModule.referenceType!!,
      listOf(DeclaredType(project, domain), referent),
    )

  class DeclaredType(project: Project, type: Type, typeArguments: List<PklType>? = null) :
    FakePklNode(project), PklDeclaredType {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitDeclaredType(this)

    override val name: PklTypeName = TypeName(project, type)
    override val typeArgumentList: PklTypeArgumentList? =
      typeArguments?.let {
        object : FakePklNode(project), PklTypeArgumentList {
          override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTypeArgumentList(this)

          override val types: List<PklType> = it
        }
      }
  }

  class TypeName(project: Project, type: Type) : FakePklNode(project), PklTypeName {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTypeName(this)

    override val moduleName: PklModuleName = ModuleName(project, type)
    override val simpleTypeName: PklSimpleTypeName = SimpleTypeName(project, type)
    override val text: String =
      "${moduleName.identifier!!.text}#${simpleTypeName.identifier!!.text}"
  }

  class ModuleName(project: Project, type: Type) : FakePklNode(project), PklModuleName {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitModuleName(this)

    override val identifier: Terminal =
      Identifier(
        project,
        when (type) {
          is Type.Class -> type.ctx.enclosingModule?.moduleName
          is Type.Alias -> type.ctx.enclosingModule?.moduleName
          is Type.Module -> ""
          else -> null
        } ?: "<module>",
      )
  }

  class SimpleTypeName(project: Project, type: Type) : FakePklNode(project), PklSimpleTypeName {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitSimpleTypeName(this)

    override val identifier: Terminal =
      Identifier(
        project,
        when (type) {
          is Type.Class -> type.ctx.name
          is Type.Alias -> type.ctx.name
          is Type.Module -> type.ctx.moduleName
          is Type.Variable -> type.ctx.text
          else -> "<type>"
        },
      )
  }

  class Identifier(project: Project, name: String) : FakePklNode(project), Terminal {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTerminal(this)

    override val text: String = name
    override val type: TokenType = TokenType.Identifier
  }
}
