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
package org.pkl.lsp.analyzers

import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.ast.TokenType.*
import org.pkl.lsp.packages.dto.Version

class ModifierAnalyzer(project: Project) : Analyzer(project) {
  companion object {
    private val MODULE_MODIFIERS = setOf(ABSTRACT, OPEN)
    private val AMENDING_MODULE_MODIFIERS = emptySet<TokenType>()
    private val CLASS_MODIFIERS = setOf(ABSTRACT, OPEN, EXTERNAL, LOCAL)
    private val TYPE_ALIAS_MODIFIERS = setOf(EXTERNAL, LOCAL)
    private val CLASS_METHOD_MODIFIERS = setOf(ABSTRACT, EXTERNAL, LOCAL, CONST)
    private val CLASS_PROPERTY_MODIFIERS = setOf(ABSTRACT, EXTERNAL, HIDDEN, LOCAL, FIXED, CONST)
    private val OBJECT_METHOD_MODIFIERS = setOf(LOCAL, CONST)
    private val OBJECT_PROPERTY_MODIFIERS = setOf(LOCAL, CONST)
  }

  override fun doAnalyze(node: PklNode, diagnosticsHolder: DiagnosticsHolder): Boolean {
    // removing module and module declaration because this will be checked in PklModuleHeader
    if (node !is PklModifierListOwner || node.modifiers == null || node is PklModule) {
      return true
    }

    var localModifier: PklNode? = null
    var abstractModifier: PklNode? = null
    var openModifier: PklNode? = null
    var hiddenModifier: PklNode? = null
    var fixedModifier: PklNode? = null
    var constModifier: PklNode? = null

    for (modifier in node.modifiers!!) {
      when (modifier.type) {
        LOCAL -> localModifier = modifier
        ABSTRACT -> abstractModifier = modifier
        OPEN -> openModifier = modifier
        HIDDEN -> hiddenModifier = modifier
        FIXED -> fixedModifier = modifier
        CONST -> constModifier = modifier
        else -> {}
      }
    }
    if (localModifier == null) {
      when (node) {
        is PklClassProperty -> {
          if (
            node.parent is PklModule &&
              (node.parent as PklModule).isAmend &&
              (hiddenModifier != null || node.typeAnnotation != null)
          ) {
            if (node.identifier != null) {
              diagnosticsHolder.addError(
                node.identifier!!,
                ErrorMessages.create("missingModifierLocal"),
              )
              return true
            }
          }
        }
        is PklObjectMethod -> {
          node.identifier?.let { identifier ->
            diagnosticsHolder.addError(identifier, ErrorMessages.create("missingModifierLocal"))
            return true
          }
        }
        is PklModuleMember -> {
          if (node.parent is PklModule && (node.parent as PklModule).isAmend) {
            node.identifier?.let { identifier ->
              diagnosticsHolder.addError(identifier, ErrorMessages.create("missingModifierLocal"))
            }
            return true
          }
        }
      }
    }

    if (abstractModifier != null && openModifier != null) {
      diagnosticsHolder.addError(
        abstractModifier,
        ErrorMessages.create("modifierAbstractConflictsWithOpen"),
      )
      diagnosticsHolder.addError(
        openModifier,
        ErrorMessages.create("modifierOpenConflictsWithAbstract"),
      )
    }

    val module = node.enclosingModule
    if (module != null && module.effectivePklVersion >= Version.PKL_VERSION_0_27) {
      // TODO: add a quick-fix
      if (constModifier != null && localModifier == null && node is PklObjectMember) {
        diagnosticsHolder.addError(
          constModifier,
          ErrorMessages.create("invalidModifierConstWithoutLocal"),
        )
      }
    }

    val (description, applicableModifiers) =
      when (node) {
        is PklModuleHeader ->
          if (node.isAmend) "amending modules" to AMENDING_MODULE_MODIFIERS
          else "modules" to MODULE_MODIFIERS
        is PklClass -> "classes" to CLASS_MODIFIERS
        is PklTypeAlias -> "typealiases" to TYPE_ALIAS_MODIFIERS
        is PklClassMethod -> "class methods" to CLASS_METHOD_MODIFIERS
        is PklClassProperty -> "class properties" to CLASS_PROPERTY_MODIFIERS
        is PklObjectProperty -> "object properties" to OBJECT_PROPERTY_MODIFIERS
        is PklObjectMethod -> "object methods" to OBJECT_METHOD_MODIFIERS
        else -> return true
      }
    for (modifier in node.modifiers!!) {
      if (modifier.type !in applicableModifiers) {
        diagnosticsHolder.addError(
          modifier,
          ErrorMessages.create("modifierIsNotApplicable", modifier.text, description),
        )
      }
    }
    return true
  }
}
