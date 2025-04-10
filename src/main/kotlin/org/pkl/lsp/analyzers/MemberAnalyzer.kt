/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.pkl.lsp.ast.PklClassProperty
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklObjectProperty
import org.pkl.lsp.ast.PklProperty
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.Type.Unknown
import org.pkl.lsp.type.computeResolvedImportType
import org.pkl.lsp.type.computeThisType

/** Analyzes object/class members, except for modifiers. */
class MemberAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, holder: DiagnosticsHolder): Boolean {

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
        checkUnresolvedProperty(node, memberType, base, holder, context)
      }
      is PklClassProperty -> {
        checkUnresolvedProperty(node, memberType, base, holder, context)
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
}
