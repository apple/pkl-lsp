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
package org.pkl.lsp.analyzers

import java.util.Collections
import java.util.IdentityHashMap
import org.pkl.lsp.Project
import org.pkl.lsp.actions.PklMakeBlankIdentifierQuickFix
import org.pkl.lsp.actions.PklRemoveUnusedImportsQuickFix
import org.pkl.lsp.ast.PklClass
import org.pkl.lsp.ast.PklForGenerator
import org.pkl.lsp.ast.PklFunctionLiteralExpr
import org.pkl.lsp.ast.PklImport
import org.pkl.lsp.ast.PklLetExpr
import org.pkl.lsp.ast.PklMethod
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklObjectBody
import org.pkl.lsp.ast.PklParameterList
import org.pkl.lsp.ast.PklProperty
import org.pkl.lsp.ast.PklTypeAlias
import org.pkl.lsp.ast.PklTypeParameter
import org.pkl.lsp.ast.PklTypedIdentifier
import org.pkl.lsp.resolvers.visitLocalDefinitions
import org.pkl.lsp.resolvers.visitUsedLocalDefinitions

class UnusedLocalDefinitionAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, diagnosticsHolder: DiagnosticsHolder): Boolean {
    if (node !is PklModule) return false
    val base = node.project.pklBaseModule
    val usedDefinitions: MutableSet<PklNode> = Collections.newSetFromMap(IdentityHashMap())
    visitUsedLocalDefinitions(node, base) { usedDefinitions.add(it) }
    visitLocalDefinitions(node) { definition ->
      if (usedDefinitions.contains(definition)) return@visitLocalDefinitions
      when (definition) {
        is PklImport ->
          diagnosticsHolder.addUnused(definition, "Unused import") {
            actions = listOf(PklRemoveUnusedImportsQuickFix(node))
          }
        is PklClass -> {
          definition.identifier?.let { identifier ->
            diagnosticsHolder.addUnused(identifier, "Unused class")
          }
        }
        is PklTypeAlias -> {
          definition.identifier?.let { identifier ->
            diagnosticsHolder.addUnused(identifier, "Unused type alias")
          }
        }
        is PklMethod -> {
          definition.identifier?.let { identifier ->
            diagnosticsHolder.addUnused(identifier, "Unused method")
          }
        }
        is PklProperty -> {
          definition.identifier?.let { identifier ->
            diagnosticsHolder.addUnused(identifier, "Unused property")
          }
        }
        is PklTypedIdentifier ->
          when (val parent = definition.parent) {
            is PklLetExpr,
            is PklForGenerator -> {
              definition.identifier?.let { identifier ->
                diagnosticsHolder.addUnused(identifier, "Unused variable") {
                  actions += listOf(PklMakeBlankIdentifierQuickFix(definition, isIgnore = true))
                }
              }
            }
            is PklParameterList -> {
              val parent =
                parent.parentOfTypes(
                  PklMethod::class,
                  PklFunctionLiteralExpr::class,
                  PklObjectBody::class,
                )
              if (parent is PklMethod && (parent.isAbstract || parent.isExternal)) {
                return@visitLocalDefinitions
              }
              definition.identifier?.let { identifier ->
                diagnosticsHolder.addUnused(identifier, "Unused parameter") {
                  actions += listOf(PklMakeBlankIdentifierQuickFix(definition, isIgnore = true))
                }
              }
            }
          }

        is PklTypeParameter -> {
          definition.identifier?.let { identifier ->
            diagnosticsHolder.addUnused(identifier, "Unused variable")
          }
        }
      }
    }
    return false
  }
}
