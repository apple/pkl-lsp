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
import org.pkl.lsp.*
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.documentation.DocCommentMemberLinkProcessor
import org.pkl.lsp.packages.Dependency
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitor
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.TypeParameterBindings
import org.pkl.lsp.util.CachedValueDataHolderBase

interface PklNode {
  val project: Project
  val span: Span
  val parent: PklNode?
  val children: List<PklNode>
  val containingFile: VirtualFile
  val enclosingModule: PklModule?
  val terminals: List<Terminal>
  /** The verbatim text of this node. */
  val text: String
  /** True if tree-sitter inserted this node. */
  val isMissing: Boolean

  fun <R> accept(visitor: PklVisitor<R>): R?

  @Suppress("UNCHECKED_CAST")
  fun <T : PklNode> parentOfTypes(vararg classes: KClass<out T>): T? {
    var node: PklNode? = parent
    while (node != null) {
      for (clazz in classes) {
        if (clazz.isInstance(node)) return node as T
      }
      node = node.parent
    }
    return null
  }
}

/** Represents an error node in tree-sitter */
interface PklError : PklNode

interface PklReference : PklNode {
  fun resolve(context: PklProject?): PklNode?
}

interface PklQualifiedIdentifier : PklNode {
  val identifiers: List<Terminal>
  val fullName: String
}

interface PklStringConstant : PklNode {
  val value: String
}

interface IdentifierOwner : PklNode {
  val identifier: Terminal?

  fun matches(line: Int, col: Int): Boolean = identifier?.span?.matches(line, col) == true
}

interface ModifierListOwner : PklNode {
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

interface PklDocCommentOwner : PklNode {
  // assertion: DocComment is always the first node
  val docComment: Terminal?
    get() {
      val terminal = children.firstOrNull() as? Terminal ?: return null
      return if (terminal.type == TokenType.DocComment) terminal else null
    }

  private val rawComment: String?
    get() {
      val doc = docComment?.text?.trim() ?: return null
      return doc.lines().joinToString("\n") { it.trimStart().removePrefix("///") }.trimIndent()
    }

  val parsedComment: String?
    get() {
      return rawComment?.let { DocCommentMemberLinkProcessor.process(it, this) }
    }

  /**
   * Returns the documentation comment of this member. For properties and methods, if this member
   * does not have a documentation comment, returns the documentation comment of the nearest
   * documented ancestor, if any.
   */
  fun effectiveDocComment(context: PklProject?): String? = parsedComment
}

sealed interface PklNavigableElement : PklNode

sealed interface PklTypeDefOrModule : PklNavigableElement, ModifierListOwner, PklDocCommentOwner

interface PklModule : PklTypeDefOrModule {
  val uri: URI
  val virtualFile: VirtualFile
  val isAmend: Boolean
  val header: PklModuleHeader?
  val imports: List<PklImport>
  val members: List<PklModuleMember>
  val typeAliases: List<PklTypeAlias>
  val classes: List<PklClass>
  val typeDefs: List<PklTypeDef>
  val typeDefsAndProperties: List<PklTypeDefOrProperty>
  val properties: List<PklClassProperty>
  val methods: List<PklClassMethod>

  fun supermodule(context: PklProject?): PklModule?

  fun cache(context: PklProject?): ModuleMemberCache

  val shortDisplayName: String
  val moduleName: String?

  /** The package dependencies of this module. */
  fun dependencies(context: PklProject?): Map<String, Dependency>?
}

/** Either [moduleClause] is set, or [moduleExtendsAmendsClause] is set. */
interface PklModuleHeader : PklNode, ModifierListOwner, PklDocCommentOwner {
  val annotations: List<PklAnnotation>

  val isAmend: Boolean
    get() = effectiveExtendsOrAmendsCluse?.isAmend ?: false

  val moduleClause: PklModuleClause?

  val moduleExtendsAmendsClause: PklModuleExtendsAmendsClause?

  val effectiveExtendsOrAmendsCluse: PklModuleExtendsAmendsClause?
    get() = moduleExtendsAmendsClause
}

interface PklModuleClause : PklNode, ModifierListOwner {
  val qualifiedIdentifier: PklQualifiedIdentifier?
  val shortDisplayName: String?
  val moduleName: String?
}

interface PklModuleExtendsAmendsClause : PklNode, PklModuleUriOwner {
  val isAmend: Boolean

  val isExtend: Boolean
}

interface PklModuleUriOwner {
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

interface PklClass : PklTypeDef, IdentifierOwner {
  val annotations: List<PklAnnotation>?
  val extends: PklType?
  val classBody: PklClassBody?
  val members: List<PklClassMember>
  val properties: List<PklClassProperty>
  val methods: List<PklClassMethod>

  fun cache(context: PklProject?): ClassMemberCache
}

interface PklClassBody : PklNode {
  val members: List<PklClassMember>
}

interface PklAnnotation : PklObjectBodyOwner {
  val type: PklType?
  val typeName: PklTypeName?
  override val objectBody: PklObjectBody?
}

interface PklTypeName : PklNode {
  val moduleName: PklModuleName?
  val simpleTypeName: PklSimpleTypeName
}

interface PklSimpleTypeName : PklNode, IdentifierOwner

interface PklModuleName : PklNode, IdentifierOwner

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
  val annotations: List<PklAnnotation>
  val objectBody: PklObjectBody?
}

interface PklMethod : PklNavigableElement, ModifierListOwner {
  val methodHeader: PklMethodHeader
  val body: PklExpr
  val name: String
}

interface PklClassMethod : PklMethod, PklModuleMember, PklClassMember {
  val annotations: List<PklAnnotation>
}

interface PklObjectMethod : PklMethod, PklObjectMember

sealed interface PklObjectMember : PklNode

interface PklObjectProperty : PklNavigableElement, PklProperty, PklObjectMember

interface PklObjectEntry : PklObjectMember {
  val keyExpr: PklExpr?
  val valueExpr: PklExpr?
  val objectBodyList: List<PklObjectBody>
}

interface PklMemberPredicate : PklObjectMember {
  val conditionExpr: PklExpr?
  val valueExpr: PklExpr?
  val objectBodyList: List<PklObjectBody>
}

interface PklForGenerator : PklObjectMember {
  val iterableExpr: PklExpr?
  val parameters: List<PklTypedIdentifier>
}

interface PklWhenGenerator : PklObjectMember {
  val conditionExpr: PklExpr?
  val thenBody: PklObjectBody?
  val elseBody: PklObjectBody?
}

interface PklObjectElement : PklObjectMember {
  val expr: PklExpr
}

interface PklObjectSpread : PklObjectMember {
  val expr: PklExpr
  val isNullable: Boolean
}

interface PklMethodHeader : PklNode, ModifierListOwner, IdentifierOwner {
  val parameterList: PklParameterList?

  val typeParameterList: PklTypeParameterList?

  val returnType: PklType?
}

sealed interface PklObjectBodyOwner : PklNode {
  val objectBody: PklObjectBody?
}

interface PklObjectBody : PklNode {
  val parameters: PklParameterList?

  val members: List<PklObjectMember>

  val properties: List<PklObjectProperty>

  val methods: List<PklObjectMethod>
}

interface PklTypeParameterList : PklNode {
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

interface PklParameterList : PklNode {
  val elements: List<PklTypedIdentifier>
}

interface PklTypeAlias : PklTypeDef, IdentifierOwner {
  val type: PklType

  fun isRecursive(context: PklProject?): Boolean
}

interface Terminal : PklNode {
  val type: TokenType
}

interface PklModuleUri : PklNode {
  val stringConstant: PklStringConstant
}

interface PklImportBase : PklNode, PklModuleUriOwner {
  val isGlob: Boolean
}

sealed interface PklImport : PklImportBase, IdentifierOwner

sealed interface PklExpr : PklNode

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

interface PklStringLiteral : PklExpr

interface PklSingleLineStringLiteral : PklStringLiteral {
  val exprs: List<PklExpr>
}

interface PklMultiLineStringLiteral : PklStringLiteral {
  val exprs: List<PklExpr>
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
    context: PklProject?,
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
  val varExpr: PklExpr
  val bodyExpr: PklExpr
  val parameter: PklTypedIdentifier
}

interface PklFunctionLiteralExpr : PklExpr {
  val expr: PklExpr
  val parameterList: PklParameterList
}

interface PklParenthesizedExpr : PklExpr {
  val expr: PklExpr?
}

interface PklTypeAnnotation : PklNode {
  val type: PklType?
}

interface PklTypedIdentifier : PklNavigableElement, IdentifierOwner {
  val typeAnnotation: PklTypeAnnotation?
  val type: PklType?
  val isUnderscore: Boolean
}

interface PklArgumentList : PklNode {
  val elements: List<PklExpr>
}

sealed interface PklType : PklNode {
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

interface PklTypeArgumentList : PklNode {
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

abstract class AbstractPklNode(
  override val project: Project,
  override val parent: PklNode?,
  protected open val ctx: TreeSitterNode,
) : CachedValueDataHolderBase(), PklNode {
  private val childrenByType: Map<KClass<out PklNode>, List<PklNode>> by lazy {
    val self = this
    // use LinkedHashMap to preserve order
    LinkedHashMap<KClass<out PklNode>, MutableList<PklNode>>().also { map ->
      for (idx in ctx.children.indices) {
        val node = ctx.children.toNode(project, self, idx) ?: continue
        when (val nodes = map[node::class]) {
          null -> map[node::class] = mutableListOf(node)
          else -> nodes.add(node)
        }
      }
    }
  }

  override val span: Span by lazy { Span.fromRange(ctx.range) }

  override val enclosingModule: PklModule? by lazy {
    var top: PklNode? = this
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

  protected fun <T : PklNode> getChild(clazz: KClass<T>): T? {
    @Suppress("UNCHECKED_CAST") return childrenByType[clazz]?.firstOrNull() as T?
  }

  protected fun <T : PklNode> getChildren(clazz: KClass<T>): List<T>? {
    @Suppress("UNCHECKED_CAST") return childrenByType[clazz] as List<T>?
  }

  override val terminals: List<Terminal> by lazy { getChildren(TerminalImpl::class) ?: emptyList() }

  override val children: List<PklNode> by lazy { childrenByType.values.flatten() }

  override val text: String by lazy { ctx.text }

  override val isMissing: Boolean by lazy { ctx.isMissing }

  override fun hashCode(): Int {
    return ctx.hashCode()
  }

  // Two nodes are the same if their contexts are the same object
  override fun equals(other: Any?): Boolean {
    return when (other) {
      null -> false
      is AbstractPklNode -> ctx === other.ctx
      else -> false
    }
  }
}

class PklErrorImpl(
  override val project: Project,
  override val parent: PklNode?,
  override val ctx: TreeSitterNode,
) : PklError, AbstractPklNode(project, parent, ctx) {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitError(this)
  }
}

fun List<TreeSitterNode>.toNode(project: Project, parent: PklNode?, idx: Int): PklNode? {
  return get(idx).toNode(project, parent)
}

private fun TreeSitterNode.toTypeNode(project: Project, parent: PklNode?): PklNode? {
  if (childCount <= 0) return null
  val childType = children.single()
  return when (childType.type) {
    "unknownType" -> PklUnknownTypeImpl(project, parent!!, childType)
    "nothingType" -> PklNothingTypeImpl(project, parent!!, childType)
    "moduleType" -> PklModuleTypeImpl(project, parent!!, childType)
    "stringLiteralType" -> PklStringLiteralTypeImpl(project, parent!!, childType)
    "declaredType" -> PklDeclaredTypeImpl(project, parent!!, childType)
    "parenthesizedType" -> PklParenthesizedTypeImpl(project, parent!!, childType)
    "functionLiteralType" -> PklFunctionTypeImpl(project, parent!!, childType)
    // TODO: for pkl-tree-sitter `*Foo` alone is a valid type
    "unionDefaultType" -> PklDefaultUnionTypeImpl(project, parent!!, childType)
    "nullableType" -> PklNullableTypeImpl(project, parent!!, childType)
    "unionType" -> PklUnionTypeImpl(project, parent!!, childType)
    "constrainedType" -> PklConstrainedTypeImpl(project, parent!!, childType)
    else -> throw AssertionError("Unexpected type: ${childType.type}")
  }
}

fun TreeSitterNode.toNode(project: Project, parent: PklNode?): PklNode? {
  return when (type) {
    // a module can never be constructed from this function
    // "module" -> PklModuleImpl(this)
    "moduleHeader" -> PklModuleHeaderImpl(project, parent!!, this)
    "moduleClause" -> PklModuleClauseImpl(project, parent!!, this)
    "importClause",
    "importGlobClause" -> PklImportImpl(project, parent!!, this)
    "extendsOrAmendsClause" -> PklModuleExtendsAmendsClauseImpl(project, parent!!, this)
    "clazz" -> PklClassImpl(project, parent!!, this)
    "classBody" -> PklClassBodyImpl(project, parent!!, this)
    // This is kinda of a hack but works because they have the same children
    "classExtendsClause" -> PklDeclaredTypeImpl(project, parent!!, this)
    "classProperty" -> PklClassPropertyImpl(project, parent!!, this)
    "methodHeader" -> PklMethodHeaderImpl(project, parent!!, this)
    "classMethod" -> PklClassMethodImpl(project, parent!!, this)
    "parameterList" -> PklParameterListImpl(project, parent!!, this)
    "argumentList" -> PklArgumentListImpl(project, parent!!, this)
    "annotation" -> PklAnnotationImpl(project, parent!!, this)
    "typeAnnotation" -> PklTypeAnnotationImpl(project, parent!!, this)
    "typedIdentifier" -> PklTypedIdentifierImpl(project, parent!!, this)
    "type" -> toTypeNode(project, parent)
    "typeArgumentList" -> PklTypeArgumentListImpl(project, parent!!, this)
    "thisExpr" -> PklThisExprImpl(project, parent!!, this)
    "outerExpr" -> PklOuterExprImpl(project, parent!!, this)
    "moduleExpr" -> PklModuleExprImpl(project, parent!!, this)
    "nullLiteral" -> PklNullLiteralExprImpl(project, parent!!, this)
    "trueLiteral" -> PklTrueLiteralExprImpl(project, parent!!, this)
    "falseLiteral" -> PklFalseLiteralExprImpl(project, parent!!, this)
    "intLiteral" -> PklIntLiteralExprImpl(project, parent!!, this)
    "floatLiteral" -> PklFloatLiteralExprImpl(project, parent!!, this)
    "throwExpr" -> PklThrowExprImpl(project, parent!!, this)
    "traceExpr" -> PklTraceExprImpl(project, parent!!, this)
    "importExpr",
    "importGlobExpr" -> PklImportExprImpl(project, parent!!, this)
    "readExpr",
    "readOrNullExpr",
    "readGlobExpr" -> PklReadExprImpl(project, parent!!, this)
    "variableExpr" -> PklUnqualifiedAccessExprImpl(project, parent!!, this)
    "propertyCallExpr" ->
      when {
        children[0].type == "super" -> PklSuperAccessExprImpl(project, parent!!, this)
        else -> PklQualifiedAccessExprImpl(project, parent!!, this)
      }
    "methodCallExpr" ->
      when (children[0].type) {
        "super" -> PklSuperAccessExprImpl(project, parent!!, this)
        "identifier" -> PklUnqualifiedAccessExprImpl(project, parent!!, this)
        else -> PklQualifiedAccessExprImpl(project, parent!!, this)
      }
    "slStringLiteral" -> PklSingleLineStringLiteralImpl(project, parent!!, this)
    "slStringLiteralPart" -> toTerminal(parent!!)
    "mlStringLiteral" -> PklMultiLineStringLiteralImpl(project, parent!!, this)
    "mlStringLiteralPart" -> toTerminal(parent!!)
    "stringConstant" -> PklStringConstantImpl(project, parent!!, this)
    "newExpr" -> PklNewExprImpl(project, parent!!, this)
    "objectLiteral",
    "variableObjectLiteral" -> PklAmendExprImpl(project, parent!!, this)
    "subscriptExpr" ->
      when {
        children[0].type == "super" -> PklSuperSubscriptExprImpl(project, parent!!, this)
        else -> PklSubscriptExprImpl(project, parent!!, this)
      }
    "unaryExpr" ->
      when (children[0].type) {
        "-" -> PklUnaryMinusExprImpl(project, parent!!, this)
        "!" -> PklLogicalNotExprImpl(project, parent!!, this)
        else -> PklNonNullExprImpl(project, parent!!, this)
      }
    "binaryExprRightAssoc" ->
      when (children[1].type) {
        "**" -> PklExponentiationExprImpl(project, parent!!, this)
        "??" -> PklNullCoalesceExprImpl(project, parent!!, this)
        "lineComment",
        "blockComment" -> null
        "ERROR" -> PklErrorImpl(project, parent!!, this)
        else -> throw RuntimeException("Unknown binary operator `${children[1].type}`")
      }
    "binaryExpr" ->
      when (children[1].type) {
        "*",
        "/",
        "~/",
        "%" -> PklMultiplicativeExprImpl(project, parent!!, this)
        "+",
        "-" -> PklAdditiveExprImpl(project, parent!!, this)
        "<",
        ">",
        "<=",
        ">=" -> PklComparisonExprImpl(project, parent!!, this)
        "==",
        "!=" -> PklEqualityExprImpl(project, parent!!, this)
        "&&" -> PklLogicalAndExprImpl(project, parent!!, this)
        "||" -> PklLogicalOrExprImpl(project, parent!!, this)
        "|>" -> PklPipeExprImpl(project, parent!!, this)
        "lineComment",
        "blockComment" -> null
        "ERROR" -> PklErrorImpl(project, parent!!, this)
        else -> throw RuntimeException("Unknown binary operator `${children[1].type}`")
      }
    "isExpr" -> PklTypeTestExprImpl(project, parent!!, this)
    "asExpr" -> PklTypeTestExprImpl(project, parent!!, this)
    "ifExpr" -> PklIfExprImpl(project, parent!!, this)
    "letExpr" -> PklLetExprImpl(project, parent!!, this)
    "functionLiteral" -> PklFunctionLiteralExprImpl(project, parent!!, this)
    "parenthesizedExpr" -> PklParenthesizedExprImpl(project, parent!!, this)
    "qualifiedIdentifier" -> PklQualifiedIdentifierImpl(project, parent!!, this)
    "objectBody" -> PklObjectBodyImpl(project, parent!!, this)
    "objectBodyParameters" -> PklParameterListImpl(project, parent!!, this)
    "_objectMember" -> children[0].toNode(project, parent)
    "objectProperty" -> PklObjectPropertyImpl(project, parent!!, this)
    "objectMethod" -> PklObjectMethodImpl(project, parent!!, this)
    "objectEntry" -> PklObjectEntryImpl(project, parent!!, this)
    "objectElement" -> PklObjectElementImpl(project, parent!!, this)
    "objectPredicate" -> PklMemberPredicateImpl(project, parent!!, this)
    "forGenerator" -> PklForGeneratorImpl(project, parent!!, this)
    "whenGenerator" -> PklWhenGeneratorImpl(project, parent!!, this)
    "objectSpread" -> PklObjectSpreadImpl(project, parent!!, this)
    "typeParameterList" -> PklTypeParameterListImpl(project, parent!!, this)
    "typeParameter" -> PklTypeParameterImpl(project, parent!!, this)
    "typeAlias" -> PklTypeAliasImpl(project, parent!!, this)
    // is TypeAnnotationContext -> Ty
    // treat modifiers as terminals; matches how we do it in pkl-intellij
    "modifier" -> children[0].toTerminal(parent!!)
    "identifier" -> toTerminal(parent!!)
    "docComment" -> toTerminal(parent!!)
    "escapeSequence" -> toTerminal(parent!!)
    // just becomes an expression
    "interpolationExpr" -> children[1].toNode(project, parent)
    "lineComment",
    "blockComment" -> null
    "ERROR" -> PklErrorImpl(project, parent!!, this)
    else -> {
      val term = toTerminal(parent!!)
      term
        ?: throw RuntimeException(
          """
          Unknown node:
            type: $type
            text: $text
        """
            .trimIndent()
        )
    }
  }
}
