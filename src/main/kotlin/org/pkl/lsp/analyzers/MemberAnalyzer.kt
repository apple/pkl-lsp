/*
 * Copyright © 2025-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.analyzers

import org.eclipse.lsp4j.DiagnosticSeverity
import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.Project
import org.pkl.lsp.actions.PklRemoveRemoveNodeQuickFix
import org.pkl.lsp.ast.PklClassMethod
import org.pkl.lsp.ast.PklClassProperty
import org.pkl.lsp.ast.PklExpr
import org.pkl.lsp.ast.PklModifierListOwner
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklObjectProperty
import org.pkl.lsp.ast.PklProperty
import org.pkl.lsp.ast.Span
import org.pkl.lsp.ast.Terminal
import org.pkl.lsp.ast.TokenType
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.Type.Unknown
import org.pkl.lsp.type.computeResolvedImportType
import org.pkl.lsp.type.computeThisType

/** Analyzes object/class members, except for modifiers. */
class MemberAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, diagnosticsHolder: DiagnosticsHolder): Boolean {

    val module = node.enclosingModule ?: return false
    val project = module.project
    val base = project.pklBaseModule
    val context = module.containingFile.pklProject

    val memberType: Type by lazy {
      node.computeResolvedImportType(
        base,
        mapOf(),
        context,
        preserveUnboundTypeVars = false,
        canInferExprBody = false,
      )
    }

    when (node) {
      is PklObjectProperty -> {
        checkUnresolvedProperty(node, memberType, base, diagnosticsHolder, context)
      }
      is PklClassProperty -> {
        checkUnresolvedProperty(node, memberType, base, diagnosticsHolder, context)
        checkAbstractProperty(node, diagnosticsHolder)
      }
      is PklClassMethod -> {
        checkAbstractMethodWithBody(node, diagnosticsHolder)
      }
    }
    return true
  }

  private fun checkUnresolvedProperty(
    property: PklProperty,
    propertyType: Type,
    base: PklBaseModule,
    holder: DiagnosticsHolder,
    context: PklProject?,
  ) {

    if (propertyType != Unknown) {
      // could determine property type -> property definition was found
      return
    }

    if (property.isDefinition(context)) return

    // this may be expensive to recompute
    // (was already computed during `element.computeDefinitionType`)
    val thisType = property.computeThisType(base, mapOf(), context)
    if (thisType == Unknown) return

    if (thisType.isSubtypeOf(base.objectType, base, context)) {
      if (thisType.isSubtypeOf(base.dynamicType, base, context)) return

      // should be able to find a definition
      val visitor = ResolveVisitors.firstElementNamed(property.name, base)
      val identifier = property.identifier ?: return
      if (Resolvers.resolveQualifiedAccess(thisType, true, base, visitor, context) == null) {
        holder.addDiagnostic(
          identifier,
          ErrorMessages.create("unresolvedProperty", property.name),
        ) {
          severity =
            if (thisType.isUnresolvedMemberFatal(base, context)) DiagnosticSeverity.Error
            else DiagnosticSeverity.Warning
          problemGroup = PklProblemGroups.unresolvedElement
        }
      }
    }
  }

  private fun PklModifierListOwner.getAbstractModifier(): PklNode? {
    val elements = modifiers ?: return null
    for (elem in elements) {
      if (elem.type == TokenType.ABSTRACT) {
        return elem
      }
    }
    return null
  }

  private fun checkAbstractProperty(node: PklClassProperty, diagnosticsHolder: DiagnosticsHolder) {
    val abstractModifier = node.getAbstractModifier() ?: return
    diagnosticsHolder.addUnused(
      node,
      "Abstract modifier is ignored for properties",
      abstractModifier.span,
    ) {
      if (node.enclosingModule?.virtualFile?.canModify() == true) {
        actions += PklRemoveRemoveNodeQuickFix("Remove abstract modifier", abstractModifier)
      }
    }
  }

  private fun checkAbstractMethodWithBody(
    node: PklClassMethod,
    diagnosticsHolder: DiagnosticsHolder,
  ) {
    if (!node.isAbstract) return
    node.body?.spanWithAssign?.let {
      diagnosticsHolder.addDiagnostic(
        node,
        "Abstract method cannot have a body",
        it,
        DiagnosticSeverity.Error,
      )
    }
  }

  private val PklExpr.spanWithAssign: Span?
    get() {
      val assignNode =
        this.prevSiblingMatching { it is Terminal && it.type == TokenType.ASSIGN } ?: return null
      return assignNode.span.endAt(span)
    }
}
