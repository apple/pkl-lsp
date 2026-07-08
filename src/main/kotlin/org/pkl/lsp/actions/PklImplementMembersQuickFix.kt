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
package org.pkl.lsp.actions

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.ImportInfo
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.Refactorings
import org.pkl.lsp.analyzers.PklDiagnostic
import org.pkl.lsp.ast.PklClass
import org.pkl.lsp.ast.PklClassMember
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklModuleMember
import org.pkl.lsp.ast.PklTypeDefOrModule
import org.pkl.lsp.ast.effectiveParentProperties
import org.pkl.lsp.ast.hasDeclaredMethod
import org.pkl.lsp.ast.hasDeclaredProperty
import org.pkl.lsp.ast.hasDefault
import org.pkl.lsp.ast.lastChildOfClass
import org.pkl.lsp.ast.lspUri
import org.pkl.lsp.ast.methods

class PklImplementMembersQuickFix(override val node: PklTypeDefOrModule) :
  PklLocalEditCodeAction(node) {
  override fun getEdits(): List<TextEdit> {
    val myModule = node.enclosingModule ?: return emptyList()
    val context = myModule.containingFile.pklProject
    val base = node.project.pklBaseModule
    val parentProperties =
      node.effectiveParentProperties(context)?.values?.let { properties ->
        buildList {
          for (prop in properties) {
            if (node.hasDeclaredProperty(prop.name)) continue
            if (prop.isAbstract) {
              add(prop)
            } else if (prop.isFixedOrConst && !prop.hasDefault(base, context)) {
              add(prop)
            }
          }
        }
      }
    val parentMethods =
      node.methods(context)?.values?.filterNot { node.hasDeclaredMethod(it.name) || !it.isAbstract }
    val parentMembers = (parentProperties ?: listOf()) + (parentMethods ?: listOf())
    if (node is PklClass) {
      return node.addMembers(parentMembers)
    } else {
      node as PklModule
      return node.addMembers(parentMembers)
    }
  }

  private fun PklTypeDefOrModule.addMembers(parentMembers: List<PklClassMember>): List<TextEdit> {
    val isModule = this is PklModule
    val myModule = enclosingModule ?: return emptyList()
    val imports = myModule.imports.mapNotNull(ImportInfo::create).toMutableList()
    val insertedImports: MutableList<TextEdit> = mutableListOf()
    val hasNoClassBody = this is PklClass && this.classBody == null
    val hasNoOrEmptyClassBody =
      this is PklClass && this.classBody.let { it == null || it.members.isEmpty() }
    val lastMember =
      when (this) {
        is PklClass -> classBody?.lastChildOfClass<PklClassMember>()
        else -> lastChildOfClass<PklModuleMember>()
      }
    val membersText = buildString {
      var isFirst = true
      if (hasNoClassBody) {
        append(" ")
      }
      if (hasNoOrEmptyClassBody) {
        append("{\n")
      }
      if (lastMember != null) {
        append("\n")
      } else if (isModule) {
        // amends header followed by no module members.
        append("\n\n")
      }
      for (parentMember in parentMembers) {
        val memberText =
          Refactorings.renderMember(
            myModule,
            parentMember,
            imports,
            insertedImports,
            useSnippets = false,
          )
        if (isFirst) {
          isFirst = false
        } else {
          append("\n\n")
        }
        if (!isModule) {
          append("  ")
        }
        append(memberText)
      }
      append("\n")
      if (hasNoOrEmptyClassBody) {
        append("}")
      }
    }
    // extends clause guaranteed to exist; quickfix only fires on extending classes/modules
    val spanToReplace =
      when {
        hasNoOrEmptyClassBody ->
          this.classBody?.span ?: this.extendsClause!!.span.firstSucceedingCaret()
        lastMember != null -> lastMember.span.firstSucceedingCaret()
        isModule -> {
          this.imports.lastOrNull()?.span?.firstSucceedingCaret()
            ?: this.header!!
              .moduleExtendsAmendsClause!!
              .span
              .firstSucceedingCaret()
              .endAt(this.span.firstSucceedingCaret())
        }
        else -> span.firstSucceedingCaret()
      }
    return insertedImports + TextEdit(spanToReplace.toRange(), membersText)
  }

  override val title: String = ErrorMessages.create("implementMembers")

  override val kind: String = CodeActionKind.QuickFix

  override val disabled: CodeActionDisabled? = null

  override fun toMessage(diagnostic: PklDiagnostic): CodeAction {
    return super.toMessage(diagnostic).apply {
      kind = ""
      edit =
        WorkspaceEdit().apply {
          changes = mapOf(node.containingFile.lspUri.toString() to getEdits())
        }
    }
  }
}
