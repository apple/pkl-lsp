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
import org.pkl.lsp.*
import org.pkl.lsp.LSPUtil.firstInstanceOf
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.packages.Dependency
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.util.CachedValue

class PklModuleImpl(override val ctx: TreeSitterNode, override val virtualFile: VirtualFile) :
  AbstractPklNode(virtualFile.project, null, ctx), PklModule {
  override val uri: URI
    get() = virtualFile.uri

  override val isAmend: Boolean by lazy { header?.moduleExtendsAmendsClause?.isAmend ?: false }

  override val header: PklModuleHeader? by lazy { getChild(PklModuleHeaderImpl::class) }

  override val members: List<PklModuleMember> by lazy {
    children.filterIsInstance<PklModuleMember>()
  }

  override val imports: List<PklImport> by lazy { children.filterIsInstance<PklImport>() }

  override val typeAliases: List<PklTypeAlias> by lazy { children.filterIsInstance<PklTypeAlias>() }

  override val classes: List<PklClass> by lazy { children.filterIsInstance<PklClass>() }

  private val extendsAmendsUri: PklModuleUri? by lazy {
    header?.moduleExtendsAmendsClause?.moduleUri
  }

  // This is cached at the VirtualFile level
  override fun supermodule(context: PklProject?): PklModule? = extendsAmendsUri?.resolve(context)

  override fun cache(context: PklProject?): ModuleMemberCache =
    project.cachedValuesManager.getCachedValue(
      "PklModule.cache(${virtualFile.uri}, ${context?.projectDir}}"
    ) {
      val cache = ModuleMemberCache.create(this, context)
      CachedValue(cache, cache.dependencies + project.pklProjectManager.syncTracker)
    }!!

  override val modifiers: List<Terminal>? by lazy { header?.moduleClause?.modifiers }

  override val typeDefs: List<PklTypeDef> by lazy { children.filterIsInstance<PklTypeDef>() }

  override val typeDefsAndProperties: List<PklTypeDefOrProperty> by lazy {
    members.filterIsInstance<PklTypeDefOrProperty>()
  }

  override val properties: List<PklClassProperty> by lazy {
    members.filterIsInstance<PklClassProperty>()
  }

  override val methods: List<PklClassMethod> by lazy { members.filterIsInstance<PklClassMethod>() }

  override val shortDisplayName: String by lazy {
    header?.moduleClause?.shortDisplayName
      ?: uri.toString().substringAfterLast('/').replace(".pkl", "")
  }

  override val moduleName: String? by lazy {
    header?.moduleClause?.moduleName ?: uri.toString().substringAfterLast('/').replace(".pkl", "")
  }

  override fun dependencies(context: PklProject?): Map<String, Dependency>? =
    containingFile.`package`?.let { project.packageManager.getResolvedDependencies(it, context) }
      ?: containingFile.pklProject?.getResolvedDependencies(context)

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModule(this)
  }
}

class PklAnnotationImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklAnnotation {
  // TODO: tree-sitter only accepts  qualified identifier, not any type
  override val type: PklType? by lazy { children.firstInstanceOf<PklType>() }

  override val typeName: PklTypeName? by lazy { (type as? PklDeclaredType)?.name }

  override val objectBody: PklObjectBody? by lazy { children.firstInstanceOf<PklObjectBody>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitAnnotation(this)
  }
}

class PklModuleClauseImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklModuleClause {
  override val qualifiedIdentifier: PklQualifiedIdentifier? by lazy {
    getChild(PklQualifiedIdentifierImpl::class)
  }

  override val modifiers: List<Terminal>? by lazy { terminals.takeWhile { it.isModifier } }

  override val shortDisplayName: String? by lazy {
    qualifiedIdentifier?.fullName?.substringAfterLast('.')
  }

  override val moduleName: String? by lazy { qualifiedIdentifier?.fullName }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModuleHeader(this)
  }
}

class PklModuleHeaderImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklModuleHeader {

  override val annotations: List<PklAnnotation> by lazy {
    children.filterIsInstance<PklAnnotation>()
  }

  override val moduleClause: PklModuleClause? by lazy {
    children.firstInstanceOf<PklModuleClause>()
  }

  override val moduleExtendsAmendsClause: PklModuleExtendsAmendsClause? by lazy {
    getChild(PklModuleExtendsAmendsClauseImpl::class)
  }

  override val modifiers: List<Terminal> by lazy { moduleClause?.modifiers ?: emptyList() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModuleDeclaration(this)
  }
}

class PklImportImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklImport {
  override val identifier: Terminal? by lazy {
    ctx.children.find { it.type == "identifier" }?.toTerminal(this)
  }

  override val isGlob: Boolean by lazy { ctx.type == "importGlobClause" }

  override val moduleUri: PklModuleUri? by lazy { PklModuleUriImpl(project, this, ctx) }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitImport(this)
  }
}

class PklModuleExtendsAmendsClauseImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklModuleExtendsAmendsClause {
  override val isAmend: Boolean
    get() = ctx.children[0].type == "amends"

  override val isExtend: Boolean
    get() = ctx.children[0].type == "extends"

  override val moduleUri: PklModuleUri? by lazy { PklModuleUriImpl(project, this, ctx) }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModuleExtendsAmendsClause(this)
  }
}

class PklClassImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklClass {
  override val extends: PklType? by lazy { getChild(PklDeclaredTypeImpl::class) }

  override val annotations: List<PklAnnotation>? by lazy { getChildren(PklAnnotationImpl::class) }

  override val classBody: PklClassBody? by lazy { getChild(PklClassBodyImpl::class) }

  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }

  override val name: String by lazy { identifier!!.text }

  override val modifiers: List<Terminal>? by lazy { terminals.takeWhile { it.isModifier } }

  override fun cache(context: PklProject?): ClassMemberCache =
    ClassMemberCache.create(this, context)

  override val typeParameterList: PklTypeParameterList? by lazy {
    getChild(PklTypeParameterListImpl::class)
  }

  override val members: List<PklClassMember> by lazy { classBody?.members ?: emptyList() }

  override val properties: List<PklClassProperty> by lazy {
    members.filterIsInstance<PklClassProperty>()
  }

  override val methods: List<PklClassMethod> by lazy { members.filterIsInstance<PklClassMethod>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitClass(this)
  }
}

class PklClassBodyImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklClassBody {
  override val members: List<PklClassMember> by lazy { children.filterIsInstance<PklClassMember>() }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitClassBody(this)
  }
}

class PklTypeParameterListImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklTypeParameterList {
  override val typeParameters: List<PklTypeParameter> by lazy {
    getChildren(PklTypeParameterImpl::class) ?: listOf()
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTypeParameterList(this)
  }
}

class PklTypeParameterImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: TreeSitterNode,
) : AbstractPklNode(project, parent, ctx), PklTypeParameter {
  override val variance: Variance? by lazy {
    val type = ctx.children[0].type
    if (type == "in") Variance.IN else if (type == "out") Variance.OUT else null
  }

  override val identifier: Terminal? by lazy { terminals.find { it.type == TokenType.Identifier } }

  override val name: String by lazy { ctx.children.find { it.type == "identifier" }!!.text }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTypeParameter(this)
  }
}
