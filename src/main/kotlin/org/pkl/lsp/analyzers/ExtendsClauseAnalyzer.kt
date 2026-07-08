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

import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.Project
import org.pkl.lsp.actions.PklAddModifierQuickFix
import org.pkl.lsp.actions.PklImplementMembersQuickFix
import org.pkl.lsp.ast.PklClass
import org.pkl.lsp.ast.PklClassExtendsClause
import org.pkl.lsp.ast.PklClassMember
import org.pkl.lsp.ast.PklClassProperty
import org.pkl.lsp.ast.PklDeclaredType
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklModuleExtendsAmendsClause
import org.pkl.lsp.ast.PklModuleType
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklType
import org.pkl.lsp.ast.PklTypeAlias
import org.pkl.lsp.ast.PklTypeDefOrModule
import org.pkl.lsp.ast.TokenType
import org.pkl.lsp.ast.declaredMethods
import org.pkl.lsp.ast.declaredProperties
import org.pkl.lsp.ast.effectiveParentProperties
import org.pkl.lsp.ast.hasDefault
import org.pkl.lsp.ast.isInStdlib
import org.pkl.lsp.ast.methods
import org.pkl.lsp.ast.resolve
import org.pkl.lsp.packages.dto.PklProject

class ExtendsClauseAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, diagnosticsHolder: DiagnosticsHolder): Boolean {
    when (node) {
      is PklModuleExtendsAmendsClause -> checkModuleExtendsClause(node, diagnosticsHolder)
      is PklClassExtendsClause -> checkClassExtendsClause(node, diagnosticsHolder, node.type)
    }
    return true
  }

  private fun checkModuleExtendsClause(
    clause: PklModuleExtendsAmendsClause,
    holder: DiagnosticsHolder,
  ) {
    if (!clause.isExtend) return
    val moduleUri = clause.moduleUri ?: return
    val context = clause.enclosingModule?.virtualFile?.pklProject
    val resolved =
      moduleUri.resolve(context) ?: return // checked by [PklModuleUriAndVersionAnnotator]
    if (!resolved.isAbstractOrOpen) {
      val moduleName = resolved.name
      holder.addError(
        moduleUri.stringConstant,
        ErrorMessages.create("cannotExtendModule", moduleName),
      ) {
        if (resolved.virtualFile.canModify()) {
          actions +=
            PklAddModifierQuickFix(
              resolved,
              ErrorMessages.create("addModuleModifier", moduleName, "open"),
              TokenType.OPEN,
            )
          actions +=
            PklAddModifierQuickFix(
              resolved,
              ErrorMessages.create("addModuleModifier", moduleName, "abstract"),
              TokenType.ABSTRACT,
            )
        }
      }
    }
    val module = clause.enclosingModule ?: return
    checkParentClassDef(clause, module, project.pklBaseModule, holder, context)
  }

  private fun checkClassExtendsClause(
    clause: PklClassExtendsClause,
    holder: DiagnosticsHolder,
    supertype: PklType?,
    visitedAliases: MutableSet<PklTypeAlias> = mutableSetOf(),
  ) {
    val context = clause.enclosingModule?.virtualFile?.pklProject

    fun checkSuperclass(clazz: PklClass): Boolean {
      if (clazz.isExternal && clause.enclosingModule?.isInStdlib != true) {
        holder.addError(clause, ErrorMessages.create("cannotExtendExternalClass", clazz.name))
        return true
      } else if (!clazz.isAbstractOrOpen) {
        holder.addError(clause, ErrorMessages.create("cannotExtendClass", clazz.name)) {
          if (clazz.enclosingModule?.virtualFile?.canModify() == true) {
            actions +=
              PklAddModifierQuickFix(
                clazz,
                ErrorMessages.create("addModuleModifier", clazz.name, "open"),
                TokenType.OPEN,
              )
            actions +=
              PklAddModifierQuickFix(
                clazz,
                ErrorMessages.create("addModuleModifier", clazz.name, "abstract"),
                TokenType.ABSTRACT,
              )
          }
        }
        return true
      }
      return false
    }

    fun checkSupermodule(module: PklModule): Boolean {
      if (module.isAbstractOrOpen) return false
      val moduleName = module.name
      holder.addError(clause, ErrorMessages.create("cannotExtendModule", moduleName)) {
        if (module.virtualFile.canModify()) {
          actions +=
            PklAddModifierQuickFix(
              module,
              ErrorMessages.create("addModuleModifier", moduleName, "open"),
              TokenType.OPEN,
            )
          actions +=
            PklAddModifierQuickFix(
              module,
              ErrorMessages.create("addModuleModifier", moduleName, "abstract"),
              TokenType.ABSTRACT,
            )
        }
      }
      return true
    }

    var supertypeError = false
    when (supertype) {
      null -> {}
      is PklDeclaredType -> {
        val typeName = supertype.name
        val resolved = typeName.resolve(context) ?: return // checked by [PklTypeNameAnnotator]

        when (resolved) {
          is PklClass -> supertypeError = checkSuperclass(resolved)

          is PklTypeAlias -> {
            // guard against circular type; e.g. `typealias Foo = Foo`
            if (visitedAliases.add(resolved)) {
              checkClassExtendsClause(clause, holder, resolved.type, visitedAliases)
            }
            return
          }

          is PklModule -> supertypeError = checkSupermodule(resolved)
        }
      }

      is PklModuleType -> supertypeError = checkSupermodule(clause.enclosingModule ?: return)

      else -> {
        holder.addError(clause, ErrorMessages.create("cannotExtendType", supertype.text))
        supertypeError = true
      }
    }
    if (!supertypeError) {
      val parent = clause.parentOfTypes(PklClass::class) ?: return
      checkParentClassDef(clause, parent, project.pklBaseModule, holder, context)
    }
  }

  private fun checkFixedOrConstMembers(
    element: PklNode,
    def: PklTypeDefOrModule,
    parentMembers: Collection<PklClassMember>,
    holder: DiagnosticsHolder,
    base: PklBaseModule,
    context: PklProject?,
  ) {
    for (member in parentMembers) {
      if (member !is PklClassProperty || !member.isFixedOrConst) continue
      val definedProperty = def.declaredProperties.find { it.name == member.name }
      if (definedProperty != null) continue
      if (member.hasDefault(base, context)) continue

      // copy Java/Kotlin error message and provide information about just the first missing
      // property.
      val entityName = if (def is PklModule) "module" else "class"
      val classOrModuleName = def.name
      val message =
        ErrorMessages.create(
          "entityDoesNotDefineDefaultValue",
          entityName,
          classOrModuleName,
          member.name,
        )
      holder.addError(element, message) {
        actions +=
          PklAddModifierQuickFix(
            def,
            ErrorMessages.create("addModuleModifier", classOrModuleName, "abstract"),
            TokenType.ABSTRACT,
          )
        actions += PklImplementMembersQuickFix(def)
      }
      return
    }
  }

  private fun checkAbstractMembers(
    element: PklNode,
    def: PklTypeDefOrModule,
    parentMembers: Collection<PklClassMember>,
    holder: DiagnosticsHolder,
  ): Boolean {
    for (parentMember in parentMembers) {
      if (!parentMember.isAbstract) continue
      val parentName = parentMember.name
      val definedMember =
        when (parentMember) {
          is PklClassProperty -> def.declaredProperties.find { it.name == parentName }
          else -> def.declaredMethods.find { it.name == parentName }
        }
      if (definedMember != null) continue

      // copy Java/Kotlin error message and provide information about just the first missing
      // method/property.
      val entityName = if (def is PklModule) "module" else "class"
      val classOrModuleName = def.name
      val memberEntityName = if (parentMember is PklClassProperty) "property" else "method"
      val message =
        ErrorMessages.create(
          "entityDoesNotImplementMember",
          entityName,
          classOrModuleName,
          memberEntityName,
          parentName,
        )
      holder.addWarning(element, message) {
        actions +=
          PklAddModifierQuickFix(
            def,
            ErrorMessages.create("addModuleModifier", def.name, "abstract"),
            TokenType.ABSTRACT,
          )
        actions += PklImplementMembersQuickFix(def)
      }
      return true
    }
    return false
  }

  private fun checkParentClassDef(
    element: PklNode,
    def: PklTypeDefOrModule,
    base: PklBaseModule,
    holder: DiagnosticsHolder,
    context: PklProject?,
  ) {
    if (def.isAbstract) return
    val parentProperties = def.effectiveParentProperties(context)?.values ?: emptyList()
    val parentMethods = def.methods(context)?.values ?: emptyList()
    val parentMembers = parentProperties + parentMethods
    if (parentMembers.isEmpty()) return
    if (checkAbstractMembers(element, def, parentMembers, holder)) return
    checkFixedOrConstMembers(element, def, parentMembers, holder, base, context)
  }
}
