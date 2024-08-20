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

import java.net.URI
import kotlin.reflect.KClass
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import org.pkl.core.parser.antlr.PklParser.*
import org.pkl.lsp.*
import org.pkl.lsp.resolvers.ResolveVisitor
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.TypeParameterBindings

interface Node {
  val project: Project
  val span: Span
  val parent: Node?
  val children: List<Node>
  val containingFile: VirtualFile
  val enclosingModule: PklModule?
  val terminals: List<Terminal>
  /** The verbatim text of this node. */
  val text: String

  fun <R> accept(visitor: PklVisitor<R>): R?

  @Suppress("UNCHECKED_CAST")
  fun <T : Node> parentOfTypes(vararg classes: KClass<out T>): T? {
    var node: Node? = parent
    while (node != null) {
      for (clazz in classes) {
        if (clazz.isInstance(node)) return node as T
      }
      node = node.parent
    }
    return null
  }

  // for syntax analyzer
  // TODO: removed when we get rid of ANTLR
  fun checkClosingDelimiter(): String? = null
}

interface PklReference : Node {
  fun resolve(): Node?
}

interface PklQualifiedIdentifier : Node {
  val identifiers: List<Terminal>
  val fullName: String
}

interface PklStringConstant : Node {
  val value: String
}

interface IdentifierOwner : Node {
  val identifier: Terminal?

  fun matches(line: Int, col: Int): Boolean = identifier?.span?.matches(line, col) == true
}

interface ModifierListOwner : Node {
  val modifiers: List<Terminal>?

  val isAbstract: Boolean
    get() = hasModifier(TokenType.ABSTRACT)

  val isExternal: Boolean
    get() = hasModifier(TokenType.EXTERNAL)

  val isHidden: Boolean
    get() = hasModifier(TokenType.HIDDEN)

  val isLocal: Boolean
    get() = hasModifier(TokenType.LOCAL)

  val isOpen: Boolean
    get() = hasModifier(TokenType.OPEN)

  val isFixed: Boolean
    get() = hasModifier(TokenType.FIXED)

  val isConst: Boolean
    get() = hasModifier(TokenType.CONST)

  val isFixedOrConst: Boolean
    get() = hasAnyModifier(TokenType.CONST, TokenType.FIXED)

  val isAbstractOrOpen: Boolean
    get() = hasAnyModifier(TokenType.ABSTRACT, TokenType.OPEN)

  val isLocalOrConstOrFixed: Boolean
    get() = hasAnyModifier(TokenType.LOCAL, TokenType.CONST, TokenType.FIXED)

  private fun hasModifier(tokenType: TokenType): Boolean {
    return modifiers?.any { it.type == tokenType } ?: false
  }

  private fun hasAnyModifier(vararg tokenSet: TokenType): Boolean {
    val modifiers = modifiers ?: return false
    return modifiers.any { tokenSet.contains(it.type) }
  }
}

interface PklDocCommentOwner : Node {
  // assertion: DocComment is always the first node
  val docComment: Terminal?
    get() {
      val terminal = children.firstOrNull() as? Terminal ?: return null
      return if (terminal.type == TokenType.DocComment) terminal else null
    }

  val parsedComment: String?
    get() {
      val doc = docComment?.text?.trim() ?: return null
      return doc.lines().joinToString("\n") { it.trimStart().removePrefix("///") }.trimIndent()
    }
}

sealed interface PklNavigableElement : Node

sealed interface PklTypeDefOrModule : PklNavigableElement, ModifierListOwner, PklDocCommentOwner

interface PklModule : PklTypeDefOrModule {
  val uri: URI
  val virtualFile: VirtualFile
  val isAmend: Boolean
  val declaration: PklModuleDeclaration?
  val imports: List<PklImport>
  val members: List<PklModuleMember>
  val typeAliases: List<PklTypeAlias>
  val classes: List<PklClass>
  val typeDefs: List<PklTypeDef>
  val typeDefsAndProperties: List<PklTypeDefOrProperty>
  val properties: List<PklClassProperty>
  val methods: List<PklClassMethod>
  val supermodule: PklModule?
  val cache: ModuleMemberCache
  val shortDisplayName: String
  val moduleName: String?
}

/** Either [moduleHeader] is set, or [moduleExtendsAmendsClause] is set. */
interface PklModuleDeclaration : Node, ModifierListOwner, PklDocCommentOwner {
  val annotations: List<PklAnnotation>

  val isAmend: Boolean
    get() = effectiveExtendsOrAmendsCluse?.isAmend ?: false

  val moduleHeader: PklModuleHeader?

  val moduleExtendsAmendsClause: PklModuleExtendsAmendsClause?

  val effectiveExtendsOrAmendsCluse: PklModuleExtendsAmendsClause?
    get() = moduleHeader?.moduleExtendsAmendsClause ?: moduleExtendsAmendsClause
}

interface PklModuleHeader : Node, ModifierListOwner {
  val qualifiedIdentifier: PklQualifiedIdentifier?
  val moduleExtendsAmendsClause: PklModuleExtendsAmendsClause?
  val shortDisplayName: String?
  val moduleName: String?
}

interface PklModuleExtendsAmendsClause : Node {
  val isAmend: Boolean

  val isExtend: Boolean

  val moduleUri: PklModuleUri?
}

sealed interface PklModuleMember : PklNavigableElement, PklDocCommentOwner, ModifierListOwner {
  val name: String
}

sealed interface PklTypeDefOrProperty : PklModuleMember

sealed interface PklTypeDef :
  PklTypeDefOrProperty, PklTypeDefOrModule, PklModuleMember, ModifierListOwner {
  val typeParameterList: PklTypeParameterList?
}

interface PklClass : PklTypeDef {
  val classHeader: PklClassHeader
  val annotations: List<PklAnnotation>?
  val classBody: PklClassBody?
  val members: List<PklClassMember>
  val properties: List<PklClassProperty>
  val methods: List<PklClassMethod>
  val cache: ClassMemberCache
}

interface PklClassBody : Node {
  val members: List<PklClassMember>
}

interface PklAnnotation : PklObjectBodyOwner {
  val type: PklType?
  val typeName: PklTypeName?
  override val objectBody: PklObjectBody?
}

interface PklTypeName : Node {
  val moduleName: PklModuleName?
  val simpleTypeName: PklSimpleTypeName
}

interface PklSimpleTypeName : Node, IdentifierOwner

interface PklModuleName : Node, IdentifierOwner

interface PklClassHeader : Node, IdentifierOwner, ModifierListOwner {
  val typeParameterList: PklTypeParameterList?
  val extends: PklType?
}

sealed interface PklClassMember : PklModuleMember, PklDocCommentOwner

sealed interface PklProperty :
  PklNavigableElement, PklReference, ModifierListOwner, IdentifierOwner {
  val name: String
  val type: PklType?
  val expr: PklExpr?
  val isDefinition: Boolean
  val typeAnnotation: PklTypeAnnotation?
}

interface PklClassProperty : PklProperty, PklModuleMember, PklClassMember, PklTypeDefOrProperty {
  val objectBody: PklObjectBody?
}

interface PklMethod : PklNavigableElement, ModifierListOwner {
  val methodHeader: PklMethodHeader
  val body: PklExpr
}

interface PklClassMethod : PklMethod, PklModuleMember, PklClassMember

interface PklObjectMethod : PklMethod, PklObjectMember {
  val name: String
}

sealed interface PklObjectMember : Node

interface PklObjectProperty : PklNavigableElement, PklProperty, PklObjectMember

interface PklObjectEntry : PklObjectMember {
  val keyExpr: PklExpr?
  val valueExpr: PklExpr?
}

interface PklMemberPredicate : PklObjectMember {
  val conditionExpr: PklExpr?
  val valueExpr: PklExpr?
  val objectBodyList: List<PklObjectBody>
}

interface PklForGenerator : PklObjectMember {
  val iterableExpr: PklExpr?
  val parameters: List<PklParameter>
}

interface PklWhenGenerator : PklObjectMember {
  val conditionExpr: PklExpr?
  val thenBody: PklObjectBody?
  val elseBody: PklObjectBody?
}

interface PklObjectElement : PklObjectMember

interface PklObjectSpread : PklObjectMember {
  val expr: PklExpr
  val isNullable: Boolean
}

interface PklMethodHeader : Node, ModifierListOwner, IdentifierOwner {
  val parameterList: PklParameterList?

  val typeParameterList: PklTypeParameterList?

  val returnType: PklType?
}

sealed interface PklObjectBodyOwner : Node {
  val objectBody: PklObjectBody?
}

interface PklObjectBody : Node {
  val parameterList: PklParameterList

  val members: List<PklObjectMember>

  val properties: List<PklObjectProperty>

  val methods: List<PklObjectMethod>
}

interface PklTypeParameterList : Node {
  val typeParameters: List<PklTypeParameter>
}

enum class Variance {
  IN,
  OUT,
}

interface PklTypeParameter : PklNavigableElement, IdentifierOwner {
  val variance: Variance?
  val name: String
}

interface PklParameterList : Node {
  val elements: List<PklParameter>
}

interface PklTypeAlias : PklTypeDef {
  val typeAliasHeader: PklTypeAliasHeader
  val type: PklType
  val isRecursive: Boolean
}

interface PklTypeAliasHeader : IdentifierOwner, ModifierListOwner {
  val typeParameterList: PklTypeParameterList?
}

interface Terminal : Node {
  val type: TokenType
}

interface PklModuleUri : Node {
  val stringConstant: PklStringConstant
}

interface PklImportBase : Node {
  val isGlob: Boolean

  val moduleUri: PklModuleUri?
}

sealed interface PklImport : PklImportBase, IdentifierOwner

sealed interface PklExpr : Node

interface PklThisExpr : PklExpr

interface PklOuterExpr : PklExpr

interface PklModuleExpr : PklExpr

interface PklNullLiteralExpr : PklExpr

interface PklTrueLiteralExpr : PklExpr

interface PklFalseLiteralExpr : PklExpr

interface PklIntLiteralExpr : PklExpr

interface PklFloatLiteralExpr : PklExpr

interface PklThrowExpr : PklExpr

interface PklTraceExpr : PklExpr {
  val expr: PklExpr?
}

interface PklImportExpr : PklImportBase, PklExpr

interface PklReadExpr : PklExpr {
  val expr: PklExpr?
  val isNullable: Boolean
  val isGlob: Boolean
}

interface PklSingleLineStringLiteral : PklExpr {
  val parts: List<SingleLineStringPart>
}

interface SingleLineStringPart : Node {
  val expr: PklExpr?
}

interface PklMultiLineStringLiteral : PklExpr {
  val parts: List<MultiLineStringPart>
}

interface MultiLineStringPart : Node {
  val expr: PklExpr?
}

interface PklNewExpr : PklExpr, PklObjectBodyOwner {
  val type: PklType?
}

interface PklAmendExpr : PklExpr, PklObjectBodyOwner {
  val parentExpr: PklExpr
  override val objectBody: PklObjectBody
}

interface PklSuperSubscriptExpr : PklExpr

interface PklAccessExpr : PklExpr, PklReference, IdentifierOwner {
  val memberNameText: String
  val isNullSafeAccess: Boolean
  val argumentList: PklArgumentList?
  val isPropertyAccess: Boolean
    get() = argumentList == null

  fun <R> resolve(
    base: PklBaseModule,
    /**
     * Optionally provide the receiver type to avoid its recomputation in case it is needed. For
     * [PklUnqualifiedAccessExpr] and [PklSuperAccessExpr], [receiverType] is the type of `this` and
     * `super`, respectively.
     */
    receiverType: Type?,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
  ): R
}

interface PklQualifiedAccessExpr : PklAccessExpr {
  val receiverExpr: PklExpr
}

interface PklSuperAccessExpr : PklAccessExpr

interface PklUnqualifiedAccessExpr : PklAccessExpr

interface PklSubscriptExpr : PklBinExpr

interface PklNonNullExpr : PklExpr {
  val expr: PklExpr
}

interface PklUnaryMinusExpr : PklExpr {
  val expr: PklExpr
}

interface PklLogicalNotExpr : PklExpr {
  val expr: PklExpr
}

interface PklBinExpr : PklExpr {
  val leftExpr: PklExpr
  val rightExpr: PklExpr
  val operator: Terminal

  fun otherExpr(thisExpr: PklExpr): PklExpr? =
    when {
      thisExpr === leftExpr -> rightExpr
      thisExpr === rightExpr -> leftExpr
      else -> null // TODO: happens in doInferExprTypeFromContext() w/ parenthesized expr
    }
}

interface PklAdditiveExpr : PklBinExpr

interface PklMultiplicativeExpr : PklBinExpr

interface PklComparisonExpr : PklBinExpr

interface PklEqualityExpr : PklBinExpr

interface PklExponentiationExpr : PklBinExpr

interface PklLogicalAndExpr : PklBinExpr

interface PklLogicalOrExpr : PklBinExpr

interface PklNullCoalesceExpr : PklBinExpr

interface PklTypeTestExpr : PklExpr {
  val expr: PklExpr?
  val type: PklType
  val operator: TypeTestOperator
}

enum class TypeTestOperator {
  IS,
  AS,
}

interface PklPipeExpr : PklBinExpr

interface PklIfExpr : PklExpr {
  val conditionExpr: PklExpr
  val thenExpr: PklExpr
  val elseExpr: PklExpr
}

interface PklLetExpr : PklExpr, IdentifierOwner {
  val varExpr: PklExpr?
  val bodyExpr: PklExpr?
  val parameter: PklParameter?
}

interface PklFunctionLiteralExpr : PklExpr {
  val expr: PklExpr?
  val parameterList: PklParameterList
}

interface PklParenthesizedExpr : PklExpr {
  val expr: PklExpr?
}

interface PklTypeAnnotation : Node {
  val type: PklType?
}

interface PklTypedIdentifier : PklNavigableElement, IdentifierOwner {
  val typeAnnotation: PklTypeAnnotation?
}

interface PklParameter : Node {
  val typedIdentifier: PklTypedIdentifier?
  val isUnderscore: Boolean
  val type: PklType?
}

interface PklArgumentList : Node {
  val elements: List<PklExpr>
}

sealed interface PklType : Node {
  fun render(): String
}

interface PklUnknownType : PklType {
  override fun render(): String = "unknown"
}

interface PklNothingType : PklType {
  override fun render(): String = "nothing"
}

interface PklModuleType : PklType {
  override fun render(): String = "module"
}

interface PklStringLiteralType : PklType {
  val stringConstant: PklStringConstant

  override fun render(): String = stringConstant.text
}

interface PklDeclaredType : PklType {
  val name: PklTypeName
  val typeArgumentList: PklTypeArgumentList?

  override fun render(): String {
    return buildString {
      append(name.text)
      if (typeArgumentList != null) {
        append(
          typeArgumentList!!.types.joinToString(", ", prefix = "<", postfix = ">") { it.render() }
        )
      }
    }
  }
}

interface PklTypeArgumentList : Node {
  val types: List<PklType>
}

interface PklParenthesizedType : PklType {
  val type: PklType

  override fun render(): String {
    return buildString {
      append('(')
      append(type.render())
      append(')')
    }
  }
}

interface PklNullableType : PklType {
  val type: PklType

  override fun render(): String {
    return "${type.render()}?"
  }
}

interface PklConstrainedType : PklType {
  val type: PklType?
  val exprs: List<PklExpr>

  override fun render(): String {
    return buildString {
      append(type?.render())
      append('(')
      // TODO: properly render expressions
      append(exprs.joinToString(", ") { it.text })
      append(')')
    }
  }
}

interface PklUnionType : PklType {
  val typeList: List<PklType>
  val leftType: PklType
  val rightType: PklType

  override fun render(): String {
    return leftType.render() + " | " + rightType.render()
  }
}

interface PklFunctionType : PklType {
  val parameterList: List<PklType>
  val returnType: PklType

  override fun render(): String {
    return buildString {
      append(parameterList.joinToString(", ", prefix = "(", postfix = ")") { it.render() })
      append(" -> ")
      append(returnType.render())
    }
  }
}

interface PklDefaultUnionType : PklType {
  val type: PklType

  override fun render(): String {
    return "*" + type.render()
  }
}

abstract class AbstractNode(
  override val project: Project,
  override val parent: Node?,
  protected open val ctx: ParseTree,
) : Node {
  private val childrenByType: Map<KClass<out Node>, List<Node>> by lazy {
    if (ctx !is ParserRuleContext) {
      return@lazy emptyMap()
    }
    val parserCtx = ctx as ParserRuleContext
    val self = this
    // use LinkedHashMap to preserve order
    LinkedHashMap<KClass<out Node>, MutableList<Node>>().also { map ->
      for (idx in parserCtx.children.indices) {
        val node = parserCtx.children.toNode(project, self, idx) ?: continue
        when (val nodes = map[node::class]) {
          null -> map[node::class] = mutableListOf(node)
          else -> nodes.add(node)
        }
      }
    }
  }

  override val span: Span by lazy {
    when (ctx) {
      is ParserRuleContext -> {
        val c = ctx as ParserRuleContext
        val begin = c.start
        val end = c.stop
        val endCol = end.charPositionInLine + 1 + end.text.length
        Span(begin.line, begin.charPositionInLine + 1, end.line, endCol)
      }
      else -> {
        ctx as TerminalNode
        val token = (ctx as TerminalNode).symbol
        val endCol = token.charPositionInLine + 1 + token.text.length
        Span(token.line, token.charPositionInLine + 1, token.line, endCol)
      }
    }
  }

  override val enclosingModule: PklModule? by lazy {
    var top: Node? = this
    // navigate the parent chain until the module is reached
    while (top != null) {
      if (top is PklModule) return@lazy top
      top = top.parent
    }
    null
  }

  override val containingFile: VirtualFile by lazy {
    enclosingModule?.virtualFile ?: throw RuntimeException("Node is not contained in a module")
  }

  protected fun <T : Node> getChild(clazz: KClass<T>): T? {
    @Suppress("UNCHECKED_CAST") return childrenByType[clazz]?.firstOrNull() as T?
  }

  protected fun <T : Node> getChildren(clazz: KClass<T>): List<T>? {
    @Suppress("UNCHECKED_CAST") return childrenByType[clazz] as List<T>?
  }

  override val terminals: List<Terminal> by lazy { getChildren(TerminalImpl::class) ?: emptyList() }

  override val children: List<Node> by lazy { childrenByType.values.flatten() }

  override val text: String by lazy { ctx.text }

  override fun hashCode(): Int {
    return ctx.hashCode()
  }

  // Two nodes are the same if their contexts are the same object
  override fun equals(other: Any?): Boolean {
    return when (other) {
      null -> false
      is AbstractNode -> ctx === other.ctx
      else -> false
    }
  }
}

fun List<ParseTree>.toNode(project: Project, parent: Node?, idx: Int): Node? {
  return get(idx).toNode(project, parent)
}

fun ParseTree.toNode(project: Project, parent: Node?): Node? {
  return when (this) {
    // a module can never be constructed from this function
    // is ModuleContext -> PklModuleImpl(this)
    is ModuleDeclContext -> PklModuleDeclarationImpl(project, parent!!, this)
    is ModuleHeaderContext -> PklModuleHeaderImpl(project, parent!!, this)
    is ImportClauseContext -> PklImportImpl(project, parent!!, this)
    is ModuleExtendsOrAmendsClauseContext ->
      PklModuleExtendsAmendsClauseImpl(project, parent!!, this)
    is ClazzContext -> PklClassImpl(project, parent!!, this)
    is ClassHeaderContext -> PklClassHeaderImpl(project, parent!!, this)
    is ClassBodyContext -> PklClassBodyImpl(project, parent!!, this)
    is ClassPropertyContext -> PklClassPropertyImpl(project, parent!!, this)
    is MethodHeaderContext -> PklMethodHeaderImpl(project, parent!!, this)
    is ClassMethodContext -> PklClassMethodImpl(project, parent!!, this)
    is ParameterListContext -> PklParameterListImpl(project, parent!!, this)
    is ParameterContext -> PklParameterImpl(project, parent!!, this)
    is ArgumentListContext -> PklArgumentListImpl(project, parent!!, this)
    is AnnotationContext -> PklAnnotationImpl(project, parent!!, this)
    is TypeAnnotationContext -> PklTypeAnnotationImpl(project, parent!!, this)
    is TypedIdentifierContext -> PklTypedIdentifierImpl(project, parent!!, this)
    is UnknownTypeContext -> PklUnknownTypeImpl(project, parent!!, this)
    is NothingTypeContext -> PklNothingTypeImpl(project, parent!!, this)
    is ModuleTypeContext -> PklModuleTypeImpl(project, parent!!, this)
    is StringLiteralTypeContext -> PklStringLiteralTypeImpl(project, parent!!, this)
    is DeclaredTypeContext -> PklDeclaredTypeImpl(project, parent!!, this)
    is ParenthesizedTypeContext -> PklParenthesizedTypeImpl(project, parent!!, this)
    is NullableTypeContext -> PklNullableTypeImpl(project, parent!!, this)
    is ConstrainedTypeContext -> PklConstrainedTypeImpl(project, parent!!, this)
    is UnionTypeContext -> PklUnionTypeImpl(project, parent!!, this)
    is DefaultUnionTypeContext -> PklDefaultUnionTypeImpl(project, parent!!, this)
    is FunctionTypeContext -> PklFunctionTypeImpl(project, parent!!, this)
    is TypeArgumentListContext -> PklTypeArgumentListImpl(project, parent!!, this)
    is ThisExprContext -> PklThisExprImpl(project, parent!!, this)
    is OuterExprContext -> PklOuterExprImpl(project, parent!!, this)
    is ModuleExprContext -> PklModuleExprImpl(project, parent!!, this)
    is NullLiteralContext -> PklNullLiteralExprImpl(project, parent!!, this)
    is TrueLiteralContext -> PklTrueLiteralExprImpl(project, parent!!, this)
    is FalseLiteralContext -> PklFalseLiteralExprImpl(project, parent!!, this)
    is IntLiteralContext -> PklIntLiteralExprImpl(project, parent!!, this)
    is FloatLiteralContext -> PklFloatLiteralExprImpl(project, parent!!, this)
    is ThrowExprContext -> PklThrowExprImpl(project, parent!!, this)
    is TraceExprContext -> PklTraceExprImpl(project, parent!!, this)
    is ImportExprContext -> PklImportExprImpl(project, parent!!, this)
    is ReadExprContext -> PklReadExprImpl(project, parent!!, this)
    is UnqualifiedAccessExprContext -> PklUnqualifiedAccessExprImpl(project, parent!!, this)
    is SingleLineStringLiteralContext -> PklSingleLineStringLiteralImpl(project, parent!!, this)
    is SingleLineStringPartContext -> SingleLineStringPartImpl(project, parent!!, this)
    is MultiLineStringLiteralContext -> PklMultiLineStringLiteralImpl(project, parent!!, this)
    is MultiLineStringPartContext -> MultiLineStringPartImpl(project, parent!!, this)
    is StringConstantContext -> PklStringConstantImpl(project, parent!!, this)
    is NewExprContext -> PklNewExprImpl(project, parent!!, this)
    is AmendExprContext -> PklAmendExprImpl(project, parent!!, this)
    is SuperAccessExprContext -> PklSuperAccessExprImpl(project, parent!!, this)
    is SuperSubscriptExprContext -> PklSuperSubscriptExprImpl(project, parent!!, this)
    is QualifiedAccessExprContext -> PklQualifiedAccessExprImpl(project, parent!!, this)
    is SubscriptExprContext -> PklSubscriptExprImpl(project, parent!!, this)
    is NonNullExprContext -> PklNonNullExprImpl(project, parent!!, this)
    is UnaryMinusExprContext -> PklUnaryMinusExprImpl(project, parent!!, this)
    is LogicalNotExprContext -> PklLogicalNotExprImpl(project, parent!!, this)
    is ExponentiationExprContext -> PklExponentiationExprImpl(project, parent!!, this)
    is MultiplicativeExprContext -> PklMultiplicativeExprImpl(project, parent!!, this)
    is AdditiveExprContext -> PklAdditiveExprImpl(project, parent!!, this)
    is ComparisonExprContext -> PklComparisonExprImpl(project, parent!!, this)
    is TypeTestExprContext -> PklTypeTestExprImpl(project, parent!!, this)
    is EqualityExprContext -> PklEqualityExprImpl(project, parent!!, this)
    is LogicalAndExprContext -> PklLogicalAndExprImpl(project, parent!!, this)
    is LogicalOrExprContext -> PklLogicalOrExprImpl(project, parent!!, this)
    is PipeExprContext -> PklPipeExprImpl(project, parent!!, this)
    is NullCoalesceExprContext -> PklNullCoalesceExprImpl(project, parent!!, this)
    is IfExprContext -> PklIfExprImpl(project, parent!!, this)
    is LetExprContext -> PklLetExprImpl(project, parent!!, this)
    is FunctionLiteralContext -> PklFunctionLiteralExprImpl(project, parent!!, this)
    is ParenthesizedExprContext -> PklParenthesizedExprImpl(project, parent!!, this)
    is QualifiedIdentifierContext -> PklQualifiedIdentifierImpl(project, parent!!, this)
    is ObjectBodyContext -> PklObjectBodyImpl(project, parent!!, this)
    is ObjectPropertyContext -> PklObjectPropertyImpl(project, parent!!, this)
    is ObjectMethodContext -> PklObjectMethodImpl(project, parent!!, this)
    is ObjectEntryContext -> PklObjectEntryImpl(project, parent!!, this)
    is MemberPredicateContext -> PklMemberPredicateImpl(project, parent!!, this)
    is ForGeneratorContext -> PklForGeneratorImpl(project, parent!!, this)
    is WhenGeneratorContext -> PklWhenGeneratorImpl(project, parent!!, this)
    is ObjectElementContext -> PklObjectElementImpl(project, parent!!, this)
    is ObjectSpreadContext -> PklObjectSpreadImpl(project, parent!!, this)
    is TypeParameterContext -> PklTypeParameterImpl(project, parent!!, this)
    is TypeParameterListContext -> PklTypeParameterListImpl(project, parent!!, this)
    is TypeAliasHeaderContext -> PklTypeAliasHeaderImpl(project, parent!!, this)
    is TypeAliasContext -> PklTypeAliasImpl(project, parent!!, this)
    // is TypeAnnotationContext -> Ty
    // treat modifiers as terminals; matches how we do it in pkl-intellij
    is ModifierContext -> {
      val terminalNode =
        this.CONST()
          ?: this.ABSTRACT()
          ?: this.HIDDEN_()
          ?: this.FIXED()
          ?: this.EXTERNAL()
          ?: this.LOCAL()
          ?: this.OPEN()
      terminalNode.toTerminal(parent!!)
    }
    is TerminalNode -> this.toTerminal(parent!!)
    else -> null
  }
}
