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
package org.pkl.lsp.ast

import org.pkl.lsp.*
import org.pkl.lsp.LSPUtil.firstInstanceOf
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitor
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.TypeParameterBindings
import org.pkl.lsp.type.computeExprType
import org.pkl.lsp.type.computeThisType

class PklThisExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklThisExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitThisExpr(this)
  }
}

class PklOuterExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklOuterExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitOuterExpr(this)
  }
}

class PklModuleExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklModuleExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModuleExpr(this)
  }
}

class PklNullLiteralExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklNullLiteralExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitNullLiteralExpr(this)
  }
}

class PklTrueLiteralExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklTrueLiteralExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTrueLiteralExpr(this)
  }
}

class PklFalseLiteralExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklFalseLiteralExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitFalseLiteralExpr(this)
  }
}

class PklIntLiteralExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklIntLiteralExpr {

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitIntLiteralExpr(this)
  }
}

class PklFloatLiteralExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklFloatLiteralExpr {

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitFloatLiteralExpr(this)
  }
}

class PklThrowExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklThrowExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitThrowExpr(this)
  }
}

class PklTraceExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklTraceExpr {
  override val expr: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTraceExpr(this)
  }
}

class PklImportExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklImportExpr {
  override val isGlob: Boolean by lazy { ctx.type == "importGlobExpr" }

  // TODO: tree-sitter has only a string constant here
  override val moduleUri: PklModuleUri? by lazy { PklModuleUriImpl(project, this, ctx) }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitImportExpr(this)
  }
}

class PklReadExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklReadExpr {
  override val expr: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }
  override val isNullable: Boolean by lazy { ctx.type == "readOrNullExpr" }
  override val isGlob: Boolean by lazy { ctx.type == "readGlobExpr" }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitReadExpr(this)
  }
}

class PklUnqualifiedAccessExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklUnqualifiedAccessExpr {
  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }
  override val memberNameText: String by lazy { identifier!!.text }
  override val argumentList: PklArgumentList? by lazy {
    children.firstInstanceOf<PklArgumentList>()
  }
  override val isNullSafeAccess: Boolean = false

  override fun resolve(context: PklProject?): PklNode? {
    val base = project.pklBaseModule
    val visitor = ResolveVisitors.firstElementNamed(memberNameText, base)
    return resolve(base, null, mapOf(), visitor, context)
  }

  override fun <R> resolve(
    base: PklBaseModule,
    receiverType: Type?,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
    context: PklProject?,
  ): R {
    return Resolvers.resolveUnqualifiedAccess(
      this,
      receiverType,
      isPropertyAccess,
      base,
      bindings,
      visitor,
      context,
    )
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitUnqualifiedAccessExpr(this)
  }
}

class PklSingleLineStringLiteralImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklSingleLineStringLiteral {
  override val exprs: List<PklExpr> by lazy { children.filterIsInstance<PklExpr>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitStringLiteral(this)
  }
}

class PklMultiLineStringLiteralImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklMultiLineStringLiteral {
  override val exprs: List<PklExpr> by lazy { children.filterIsInstance<PklExpr>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitMlStringLiteral(this)
  }
}

class PklNewExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklNewExpr {
  override val type: PklType? by lazy { children.firstInstanceOf<PklType>() }
  override val objectBody: PklObjectBody? by lazy { children.firstInstanceOf<PklObjectBody>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitNewExpr(this)
  }
}

class PklAmendExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklAmendExpr {
  override val parentExpr: PklExpr by lazy {
    val expr = children.firstInstanceOf<PklExpr>()
    // tree-sitter can either have an identifier here or an expression
    if (expr != null) expr
    else {
      val ident = children.firstInstanceOf<Terminal>()!!
      if (ident.type != TokenType.Identifier) {
        throw RuntimeException("Amend expression has no expression")
      }
      PklUnqualifiedAccessExprImpl(project, parent, ctx)
    }
  }
  override val objectBody: PklObjectBody by lazy { children.firstInstanceOf<PklObjectBody>()!! }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitAmendExpr(this)
  }
}

class PklSuperAccessExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklSuperAccessExpr {
  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }
  override val memberNameText: String by lazy { identifier!!.text }
  override val isNullSafeAccess: Boolean = false
  override val argumentList: PklArgumentList? by lazy {
    children.firstInstanceOf<PklArgumentList>()
  }

  override fun resolve(context: PklProject?): PklNode? {
    val base = project.pklBaseModule
    val visitor = ResolveVisitors.firstElementNamed(memberNameText, base)
    return resolve(base, null, mapOf(), visitor, context)
  }

  override fun <R> resolve(
    base: PklBaseModule,
    receiverType: Type?,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
    context: PklProject?,
  ): R {
    // TODO: Pkl doesn't currently enforce that `super.foo`
    // has the same type as `this.foo` if `super.foo` is defined in a superclass.
    // In particular, covariant property types are used in the wild.
    val thisType = receiverType ?: computeThisType(base, bindings, context)
    return Resolvers.resolveQualifiedAccess(thisType, isPropertyAccess, base, visitor, context)
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitSuperAccessExpr(this)
  }
}

class PklSuperSubscriptExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklSuperSubscriptExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitSuperSubscriptExpr(this)
  }
}

class PklQualifiedAccessExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklQualifiedAccessExpr {
  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }
  override val memberNameText: String by lazy { identifier!!.text }
  override val isNullSafeAccess: Boolean by lazy { ctx.children[1].type == ".?" }
  override val argumentList: PklArgumentList? by lazy {
    children.firstInstanceOf<PklArgumentList>()
  }
  override val receiverExpr: PklExpr by lazy { children.firstInstanceOf<PklExpr>()!! }

  override fun resolve(context: PklProject?): PklNode? {
    val base = project.pklBaseModule
    val visitor = ResolveVisitors.firstElementNamed(memberNameText, base)
    // TODO: check if receiver is `module`
    return resolve(base, null, mapOf(), visitor, context)
  }

  override fun <R> resolve(
    base: PklBaseModule,
    receiverType: Type?,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
    context: PklProject?,
  ): R {
    val myReceiverType: Type = receiverType ?: receiverExpr.computeExprType(base, bindings, context)
    return Resolvers.resolveQualifiedAccess(
      myReceiverType,
      isPropertyAccess,
      base,
      visitor,
      context,
    )
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitQualifiedAccessExpr(this)
  }
}

class PklSubscriptExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklSubscriptExpr {
  override val leftExpr: PklExpr by lazy { ctx.children[0].toNode(project, this) as PklExpr }
  override val rightExpr: PklExpr by lazy { ctx.children[2].toNode(project, this) as PklExpr }
  override val operator: Terminal by lazy { terminals[0] }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitSubscriptExpr(this)
  }
}

class PklNonNullExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklNonNullExpr {
  override val expr: PklExpr by lazy { children.firstInstanceOf<PklExpr>()!! }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitNonNullExpr(this)
  }
}

class PklUnaryMinusExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklUnaryMinusExpr {
  override val expr: PklExpr by lazy { children.firstInstanceOf<PklExpr>()!! }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitUnaryMinusExpr(this)
  }
}

class PklLogicalNotExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklLogicalNotExpr {
  override val expr: PklExpr by lazy { children.firstInstanceOf<PklExpr>()!! }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitLogicalNotExpr(this)
  }
}

abstract class PklBinExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  open val tsOperator: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklBinExpr {
  private val exprs: List<PklExpr> by lazy { children.filterIsInstance<PklExpr>() }
  override val leftExpr: PklExpr by lazy { exprs[0] }
  override val rightExpr: PklExpr by lazy { exprs[1] }
  override val operator: Terminal by lazy { tsOperator.toTerminal(parent)!! }
}

class PklAdditiveExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklAdditiveExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitAdditiveExpr(this)
  }
}

class PklMultiplicativeExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklMultiplicativeExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitMultiplicativeExpr(this)
  }
}

class PklComparisonExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklComparisonExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitComparisonExpr(this)
  }
}

class PklEqualityExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklEqualityExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitEqualityExpr(this)
  }
}

class PklExponentiationExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklExponentiationExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitExponentiationExpr(this)
  }
}

class PklLogicalAndExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklLogicalAndExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitLogicalAndExpr(this)
  }
}

class PklLogicalOrExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklLogicalOrExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitLogicalOrExpr(this)
  }
}

class PklNullCoalesceExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklNullCoalesceExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitNullCoalesceExpr(this)
  }
}

class PklTypeTestExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklTypeTestExpr {
  override val expr: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }
  override val type: PklType by lazy { children.firstInstanceOf<PklType>()!! }
  override val operator: TypeTestOperator by lazy {
    if (ctx.type == "isExpr") TypeTestOperator.IS else TypeTestOperator.AS
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTypeTestExpr(this)
  }
}

class PklPipeExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
  override val tsOperator: TreeSitterNode,
) : PklBinExprImpl(project, parent, ctx, tsOperator), PklPipeExpr {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitPipeExpr(this)
  }
}

class PklIfExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklIfExpr {
  private val exprs by lazy { children.filterIsInstance<PklExpr>() }
  override val conditionExpr: PklExpr by lazy { exprs[0] }
  override val thenExpr: PklExpr by lazy { exprs[1] }
  override val elseExpr: PklExpr by lazy { exprs[2] }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitIfExpr(this)
  }
}

class PklLetExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklLetExpr {
  private val exprs by lazy { children.filterIsInstance<PklExpr>() }
  override val identifier: Terminal? by lazy { parameter.identifier }
  override val varExpr: PklExpr by lazy { exprs[0] }
  override val bodyExpr: PklExpr by lazy { exprs[1] }
  override val parameter: PklTypedIdentifier by lazy {
    children.firstInstanceOf<PklTypedIdentifier>()!!
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitLetExpr(this)
  }
}

class PklFunctionLiteralExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklFunctionLiteralExpr {
  override val expr: PklExpr by lazy { children.firstInstanceOf<PklExpr>()!! }
  override val parameterList: PklParameterList by lazy {
    children.firstInstanceOf<PklParameterList>()!!
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitFunctionLiteral(this)
  }
}

class PklParenthesizedExprImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklParenthesizedExpr {
  override val expr: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitParenthesizedExpr(this)
  }
}

class PklTypedIdentifierImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklTypedIdentifier {
  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }
  override val typeAnnotation: PklTypeAnnotation? by lazy {
    children.firstInstanceOf<PklTypeAnnotation>()
  }
  override val type: PklType? by lazy { typeAnnotation?.type }
  override val isUnderscore: Boolean by lazy { typeAnnotation == null && identifier!!.text == "_" }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTypedIdentifier(this)
  }
}

class PklArgumentListImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklArgumentList {
  override val elements: List<PklExpr> by lazy { children.filterIsInstance<PklExpr>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitArgumentList(this)
  }
}
