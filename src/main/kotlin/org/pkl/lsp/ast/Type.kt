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
package org.pkl.lsp.ast

import io.github.treesitter.jtreesitter.Node
import org.pkl.lsp.LspUtil.firstInstanceOf
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.Project
import org.pkl.lsp.packages.dto.PklProject

class PklTypeAnnotationImpl(project: Project, override val parent: PklNode, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklTypeAnnotation {
  override val type: PklType? by lazy { children.firstInstanceOf<PklType>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTypeAnnotation(this)
  }
}

class PklUnknownTypeImpl(project: Project, override val parent: PklNode, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklUnknownType {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitUnknownType(this)
  }
}

class PklNothingTypeImpl(project: Project, override val parent: PklNode, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklNothingType {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitNothingType(this)
  }
}

class PklModuleTypeImpl(project: Project, override val parent: PklNode, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklModuleType {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModuleType(this)
  }
}

class PklStringLiteralTypeImpl(project: Project, override val parent: PklNode, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklStringLiteralType {
  // TODO: check if `stringConstant` is a child
  override val stringConstant: PklStringConstant by lazy {
    children.firstInstanceOf<PklStringConstant>()!!
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitStringLiteralType(this)
  }
}

class PklDeclaredTypeImpl(project: Project, override val parent: PklNode, override val ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklDeclaredType {
  override val name: PklTypeName by lazy {
    toTypeName(children.firstInstanceOf<PklQualifiedIdentifier>()!!)
  }

  override val typeArgumentList: PklTypeArgumentList? by lazy {
    children.firstInstanceOf<PklTypeArgumentList>()
  }

  private fun toTypeName(ident: PklQualifiedIdentifier): PklTypeName {
    return PklTypeNameImpl(project, ident, ctx.children.find { it.type == "qualifiedIdentifier" }!!)
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitDeclaredType(this)
  }
}

class PklTypeArgumentListImpl(
  project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklTypeArgumentList {
  override val types: List<PklType> by lazy { children.filterIsInstance<PklType>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTypeArgumentList(this)
  }
}

class PklParenthesizedTypeImpl(
  project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklParenthesizedType {
  override val type: PklType by lazy { children.firstInstanceOf<PklType>()!! }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitParenthesizedType(this)
  }
}

class PklNullableTypeImpl(project: Project, override val parent: PklNode, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklNullableType {
  override val type: PklType by lazy { children.firstInstanceOf<PklType>()!! }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitNullableType(this)
  }
}

class PklConstrainedTypeImpl(
  project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklConstrainedType {
  override val type: PklType? by lazy { children.firstInstanceOf<PklType>() }
  override val exprs: List<PklExpr> by lazy { children.filterIsInstance<PklExpr>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitConstrainedType(this)
  }
}

class PklUnionTypeImpl(project: Project, override val parent: PklNode, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklUnionType {
  override val typeList: List<PklType> by lazy { children.filterIsInstance<PklType>() }
  override val leftType: PklType by lazy { typeList[0] }
  override val rightType: PklType by lazy { typeList[1] }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitUnionType(this)
  }
}

class PklFunctionTypeImpl(project: Project, override val parent: PklNode, override val ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklFunctionType {
  override val parameterList: List<PklType> by lazy {
    children.filterIsInstance<PklType>().dropLast(1)
  }
  override val returnType: PklType by lazy { children.filterIsInstance<PklType>().last() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitFunctionType(this)
  }
}

class PklDefaultUnionTypeImpl(project: Project, override val parent: PklNode, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklDefaultUnionType {
  override val type: PklType by lazy { children.firstInstanceOf<PklType>()!! }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitDefaultUnionType(this)
  }
}

class PklTypeAliasImpl(project: Project, override val parent: PklNode?, ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklTypeAlias {
  override val modifiers: List<Terminal>? by lazy { terminals.filter { it.isModifier } }
  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }
  override val name: String by lazy { identifier!!.text }
  override val type: PklType by lazy { children.firstInstanceOf<PklType>()!! }

  override fun isRecursive(context: PklProject?): Boolean = isRecursive(mutableSetOf(), context)

  override val typeParameterList: PklTypeParameterList? by lazy {
    children.firstInstanceOf<PklTypeParameterList>()
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTypeAlias(this)
  }
}

class PklTypeNameImpl(project: Project, ident: PklQualifiedIdentifier, override val ctx: Node) :
  AbstractPklNode(project, ident.parent, ctx), PklTypeName {
  override val moduleName: PklModuleName? by lazy {
    // if there's only 1 identifier it's not qualified, therefore, there's no module name
    if (ctx.childCount > 1) {
      PklModuleNameImpl(project, this, ident.identifiers[0], ctx.children[0])
    } else null
  }
  override val simpleTypeName: PklSimpleTypeName by lazy {
    PklSimpleTypeNameImpl(project, this, ident.identifiers.last(), ctx.children.last())
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTypeName(this)
  }
}

class PklSimpleTypeNameImpl(
  project: Project,
  parent: PklNode,
  terminal: Terminal,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklSimpleTypeName {
  override val identifier: Terminal? by lazy { terminal }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitSimpleTypeName(this)
  }
}

class PklModuleNameImpl(
  project: Project,
  parent: PklNode,
  terminal: Terminal,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklModuleName {
  override val identifier: Terminal? by lazy { terminal }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModuleName(this)
  }
}
