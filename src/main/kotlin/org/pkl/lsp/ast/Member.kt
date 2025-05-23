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
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.computeThisType
import org.pkl.lsp.util.CachedValue

class PklClassPropertyImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklClassProperty {
  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }

  override val modifiers: List<Terminal> by lazy { terminals.filter { it.isModifier } }

  override val annotations: List<PklAnnotation> by lazy {
    children.filterIsInstance<PklAnnotation>()
  }

  override val typeAnnotation: PklTypeAnnotation? by lazy {
    children.firstInstanceOf<PklTypeAnnotation>()
  }

  override val expr: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }

  // TODO: in tree-sitter this has multiple bodies
  override val objectBody: PklObjectBody? by lazy { getChild(PklObjectBodyImpl::class) }

  override val name: String by lazy { identifier!!.text }

  override val type: PklType? by lazy { typeAnnotation?.type }

  override fun isDefinition(context: PklProject?): Boolean =
    when {
      type != null -> true
      isLocalOrConstOrFixed -> true
      else -> {
        when (val owner = this.parentOfTypes(PklModule::class, PklClass::class)) {
          is PklModule -> {
            if (owner.header?.moduleExtendsAmendsClause?.isAmend == true) false
            else
              when (val supermodule = owner.supermodule(context)) {
                null ->
                  !project.pklBaseModule.moduleType.ctx.cache(context).properties.containsKey(name)
                else -> !supermodule.cache(context).properties.containsKey(name)
              }
          }
          is PklClass -> {
            owner.superclass(context)?.let { superclass ->
              return !superclass.cache(context).properties.containsKey(name)
            }
            owner.supermodule(context)?.let { supermodule ->
              return !supermodule.cache(context).properties.containsKey(name)
            }
            true
          }
          else -> false
        }
      }
    }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitClassProperty(this)
  }

  override fun resolve(context: PklProject?): PklNode? {
    val base = project.pklBaseModule
    val visitor = ResolveVisitors.firstElementNamed(name, base)
    return when {
      type != null -> this
      isLocal -> this
      else -> {
        val receiverType = computeThisType(base, mapOf(), context)
        Resolvers.resolveQualifiedAccess(receiverType, true, base, visitor, context)
      }
    }
  }

  @Suppress("DuplicatedCode")
  override fun effectiveDocComment(context: PklProject?): String? {
    if (parsedComment != null) return parsedComment
    val clazz = parentOfType<PklClass>() ?: return null
    clazz.eachSuperclassOrModule(context) { typeDef ->
      when (typeDef) {
        is PklClass ->
          typeDef.cache(context).properties[name]?.parsedComment?.let {
            return it
          }
        is PklModule ->
          typeDef.cache(context).properties[name]?.parsedComment?.let {
            return it
          }
        else -> {}
      }
    }
    return null
  }
}

class PklClassMethodImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklClassMethod {
  override val identifier: Terminal? by lazy { methodHeader.identifier }

  override val methodHeader: PklMethodHeader by lazy { getChild(PklMethodHeaderImpl::class)!! }

  override val name: String by lazy { methodHeader.identifier!!.text }

  override val modifiers: List<Terminal>? by lazy { methodHeader.modifiers }

  override val annotations: List<PklAnnotation> by lazy {
    children.filterIsInstance<PklAnnotation>()
  }

  override val body: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitClassMethod(this)
  }

  @Suppress("DuplicatedCode")
  override fun effectiveDocComment(context: PklProject?): String? {
    if (parsedComment != null) return parsedComment
    val clazz = parentOfType<PklClass>() ?: return null
    clazz.eachSuperclassOrModule(context) { typeDef ->
      when (typeDef) {
        is PklClass ->
          typeDef.cache(context).methods[name]?.parsedComment?.let {
            return it
          }
        is PklModule ->
          typeDef.cache(context).methods[name]?.parsedComment?.let {
            return it
          }
        else -> {}
      }
    }
    return null
  }
}

class PklMethodHeaderImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklMethodHeader {
  override val parameterList: PklParameterList? by lazy { getChild(PklParameterListImpl::class) }

  override val typeParameterList: PklTypeParameterList? by lazy {
    getChild(PklTypeParameterListImpl::class)
  }

  override val modifiers: List<Terminal> by lazy { terminals.filter { it.isModifier } }

  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }

  override val returnType: PklType? by lazy { children.firstInstanceOf<PklTypeAnnotation>()?.type }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitMethodHeader(this)
  }
}

class PklParameterListImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklParameterList {
  override val elements: List<PklTypedIdentifier> by lazy {
    children.filterIsInstance<PklTypedIdentifier>()
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitParameterList(this)
  }
}

class PklObjectBodyImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklObjectBody {
  private val lock = Object()

  override val parameters: PklParameterList? by lazy {
    children.firstInstanceOf<PklParameterList>()
  }

  override val members: List<PklObjectMember> by lazy {
    children.filterIsInstance<PklObjectMember>()
  }

  override val properties: List<PklObjectProperty> by lazy {
    members.filterIsInstance<PklObjectProperty>()
  }

  override val methods: List<PklObjectMethod> by lazy {
    members.filterIsInstance<PklObjectMethod>()
  }

  override fun isConstScope(): Boolean {
    return project.cachedValuesManager.getCachedValue(this, "isConstScope()", lock) {
      val parentProperty = parentOfTypes(PklProperty::class, PklMethod::class)
      val isConstScope =
        if (parentProperty?.isConst == true) {
          true
        } else {
          val parentObj = parentOfTypes(PklObjectBody::class)
          parentObj?.isConstScope() == true
        }
      CachedValue(isConstScope, this.containingFile)
    }!!
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitObjectBody(this)
  }
}

class PklObjectPropertyImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklObjectProperty {
  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }
  override val modifiers: List<Terminal> by lazy { terminals.filter { it.isModifier } }
  override val name: String by lazy { identifier!!.text }
  override val type: PklType? by lazy { typeAnnotation?.type }
  override val expr: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }
  override val typeAnnotation: PklTypeAnnotation? by lazy {
    children.firstInstanceOf<PklTypeAnnotation>()
  }

  override fun isDefinition(context: PklProject?): Boolean = type != null || isLocal

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitObjectProperty(this)
  }

  override fun resolve(context: PklProject?): PklNode? {
    val base = project.pklBaseModule
    val visitor = ResolveVisitors.firstElementNamed(name, base)
    return when {
      type != null -> this
      isLocal -> this
      else -> {
        val receiverType = computeThisType(base, mapOf(), context)
        Resolvers.resolveQualifiedAccess(receiverType, true, base, visitor, context)
      }
    }
  }
}

class PklObjectMethodImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklObjectMethod {
  override val identifier: Terminal? by lazy { methodHeader.identifier }
  override val methodHeader: PklMethodHeader by lazy { getChild(PklMethodHeaderImpl::class)!! }
  override val modifiers: List<Terminal>? by lazy { methodHeader.modifiers }
  override val body: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }
  override val name: String by lazy { methodHeader.identifier!!.text }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitObjectMethod(this)
  }
}

class PklObjectEntryImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklObjectEntry {
  override val keyExpr: PklExpr? by lazy { getChildByFieldName("key") as PklExpr? }

  override val valueExpr: PklExpr? by lazy { getChildByFieldName("valueExpr") as PklExpr? }

  override val objectBodyList: List<PklObjectBody> by lazy {
    children.filterIsInstance<PklObjectBody>()
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitObjectEntry(this)
  }
}

class PklMemberPredicateImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklMemberPredicate {
  override val conditionExpr: PklExpr? by lazy { getChildByFieldName("conditionExpr") as PklExpr? }

  override val valueExpr: PklExpr? by lazy { getChildByFieldName("valueExpr") as PklExpr? }

  override val objectBodyList: List<PklObjectBody> by lazy {
    children.filterIsInstance<PklObjectBody>()
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitMemberPredicate(this)
  }
}

class PklForGeneratorImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklForGenerator {
  override val iterableExpr: PklExpr? by lazy { children.firstInstanceOf<PklExpr>() }
  override val parameters: List<PklTypedIdentifier> by lazy {
    children.filterIsInstance<PklTypedIdentifier>()
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitForGenerator(this)
  }
}

class PklWhenGeneratorImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklWhenGenerator {
  override val conditionExpr: PklExpr? by lazy { getChildByFieldName("conditionExpr") as PklExpr? }

  override val thenBody: PklObjectBody? by lazy {
    getChildByFieldName("thenBody") as PklObjectBody?
  }

  override val elseBody: PklObjectBody? by lazy {
    getChildByFieldName("elseBody") as PklObjectBody?
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitWhenGenerator(this)
  }
}

class PklObjectElementImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklObjectElement {
  override val expr: PklExpr by lazy { children.firstInstanceOf<PklExpr>()!! }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitObjectElement(this)
  }
}

class PklObjectSpreadImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklObjectSpread {
  override val expr: PklExpr by lazy { children.firstInstanceOf<PklExpr>()!! }
  override val isNullable: Boolean by lazy { ctx.children[0].type == "...?" }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitObjectSpread(this)
  }
}
