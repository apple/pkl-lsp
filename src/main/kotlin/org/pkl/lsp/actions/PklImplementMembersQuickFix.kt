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
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.analyzers.PklDiagnostic
import org.pkl.lsp.ast.PklClass
import org.pkl.lsp.ast.PklClassMember
import org.pkl.lsp.ast.PklClassMethod
import org.pkl.lsp.ast.PklClassProperty
import org.pkl.lsp.ast.PklConstrainedType
import org.pkl.lsp.ast.PklDeclaredType
import org.pkl.lsp.ast.PklDefaultUnionType
import org.pkl.lsp.ast.PklExpr
import org.pkl.lsp.ast.PklFunctionType
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklModuleType
import org.pkl.lsp.ast.PklNothingType
import org.pkl.lsp.ast.PklNullableType
import org.pkl.lsp.ast.PklParenthesizedType
import org.pkl.lsp.ast.PklStringLiteralType
import org.pkl.lsp.ast.PklType
import org.pkl.lsp.ast.PklTypeCastExpr
import org.pkl.lsp.ast.PklTypeDefOrModule
import org.pkl.lsp.ast.PklTypeTestExpr
import org.pkl.lsp.ast.PklUnionType
import org.pkl.lsp.ast.PklUnknownType
import org.pkl.lsp.ast.Terminal
import org.pkl.lsp.ast.TokenType
import org.pkl.lsp.ast.appendIdentifier
import org.pkl.lsp.ast.effectiveParentProperties
import org.pkl.lsp.ast.findOrInsertImport
import org.pkl.lsp.ast.hasDeclaredMethod
import org.pkl.lsp.ast.hasDeclaredProperty
import org.pkl.lsp.ast.isInPklBaseModule
import org.pkl.lsp.ast.lastChildOfClass
import org.pkl.lsp.ast.lspUri
import org.pkl.lsp.ast.methods
import org.pkl.lsp.ast.resolve

class PklImplementMembersQuickFix(override val node: PklTypeDefOrModule) :
  PklLocalEditCodeAction(node) {
  override fun getEdits(): List<TextEdit> {
    val myModule = node.enclosingModule ?: return emptyList()
    val context = myModule.containingFile.pklProject
    val parentProperties =
      node.effectiveParentProperties(context)?.values?.filterNot {
        node.hasDeclaredProperty(it.name)
      }
    val parentMethods = node.methods(context)?.values?.filterNot { node.hasDeclaredMethod(it.name) }
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
    val lastMember = lastChildOfClass<PklClassMember>()
    val membersText = buildString {
      var isFirst = true
      if (hasNoClassBody) {
        append(" ")
      }
      if (hasNoOrEmptyClassBody) {
        append("{\n")
      }
      if (isModule || lastMember != null) {
        append("\n\n")
      }
      for (parentMember in parentMembers) {
        val memberText = renderMember(myModule, parentMember, imports, insertedImports) ?: continue
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
      if (hasNoOrEmptyClassBody) {
        append("\n}")
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

  private fun renderMember(
    myModule: PklModule,
    parentMember: PklClassMember,
    importList: MutableList<ImportInfo>,
    insertedImports: MutableList<TextEdit>,
  ): String? {
    return when {
      parentMember.isAbstract && parentMember is PklClassProperty -> {
        renderProperty(myModule, parentMember, importList, insertedImports)
      }

      parentMember.isAbstract && parentMember is PklClassMethod -> {
        renderMethod(myModule, parentMember, importList, insertedImports)
      }

      parentMember is PklClassProperty && parentMember.isFixedOrConst -> {
        renderFixedConstProperty(parentMember)
      }

      else -> null
    }
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

  private fun renderProperty(
    myModule: PklModule,
    originalProperty: PklClassProperty,
    imports: MutableList<ImportInfo>,
    insertedImports: MutableList<TextEdit>,
  ): String {
    assert(originalProperty.identifierName != null)
    return buildString {
      if (appendModifiersWithoutAbstract(originalProperty.modifiers)) {
        append(" ")
      }
      appendIdentifier(originalProperty.identifierName!!)
      val type = originalProperty.type
      if (type != null) {
        append(": ")
        appendType(type, myModule, imports, insertedImports)
      }
      append(" = TODO()")
    }
  }

  private fun renderMethod(
    myModule: PklModule,
    originalMethod: PklClassMethod,
    imports: MutableList<ImportInfo>,
    insertedImports: MutableList<TextEdit>,
  ): String {
    return buildString {
      if (appendModifiersWithoutAbstract(originalMethod.modifiers)) {
        append(' ')
      }
      append("function ")
      appendIdentifier(originalMethod.identifierName!!)
      append('(')
      originalMethod.methodHeader.parameterList?.let { paramList ->
        var isFirst = true
        for (param in paramList.elements) {
          if (isFirst) {
            isFirst = false
          } else {
            append(", ")
          }
          appendIdentifier(param.identifierName!!)
          val type = param.type
          if (type != null) {
            append(": ")
            appendType(type, myModule, imports, insertedImports)
          }
        }
      }
      append(')')
      val returnType = originalMethod.methodHeader.returnType
      if (returnType != null) {
        append(": ")
        appendType(returnType, myModule, imports, insertedImports)
      }
      append(" = TODO()")
    }
  }

  private fun renderFixedConstProperty(property: PklClassProperty): String {
    return buildString {
      if (appendModifiersWithoutAbstract(property.modifiers)) {
        append(" ")
      }
      appendIdentifier(property.identifierName!!)
      append(" = TODO()")
    }
  }

  private fun StringBuilder.appendModifiersWithoutAbstract(modifierList: List<Terminal>?): Boolean {
    var isFirst = true
    if (modifierList == null) return false
    for (modifier in modifierList) {
      if (modifier.type != TokenType.ABSTRACT) {
        if (isFirst) {
          isFirst = false
        } else {
          append(" ")
        }
        append(modifier.text)
      }
    }
    return !isFirst
  }

  private fun StringBuilder.appendType(
    type: PklType,
    myModule: PklModule,
    imports: MutableList<ImportInfo>,
    insertedImports: MutableList<TextEdit>,
  ) {
    type.accept(PklTypeRenderer(myModule, imports, insertedImports, this))
  }
}

class PklTypeRenderer(
  private val myModule: PklModule,
  private val imports: MutableList<ImportInfo>,
  private val insertedImports: MutableList<TextEdit>,
  private val sb: StringBuilder,
) : PklVisitor<Unit>() {
  private fun renderSimpleTypeName(o: PklDeclaredType) {
    val resolvedType = o.name.resolve(myModule.containingFile.pklProject) as? PklTypeDefOrModule
    val module = resolvedType?.enclosingModule
    if (module == myModule) {
      sb.append(o.name.simpleTypeName.text)
      return
    }
    when {
      resolvedType == null -> sb.append(o.name.simpleTypeName.identifier!!.text)
      resolvedType.isInPklBaseModule || resolvedType.enclosingModule == myModule ->
        sb.appendIdentifier(resolvedType.name)

      else -> {
        val importName =
          myModule.findOrInsertImport(resolvedType.enclosingModule!!, imports, insertedImports)
        sb.appendIdentifier(importName)
        if (resolvedType !is PklModule) {
          sb.append(".")
          sb.appendIdentifier(resolvedType.name)
        }
      }
    }
  }

  override fun visitDeclaredType(node: PklDeclaredType) {
    renderSimpleTypeName(node)
    val argumentList = node.typeArgumentList
    if (argumentList != null && argumentList.types.isNotEmpty()) {
      sb.append("<")
      var isFirst = true
      for (elem in argumentList.types) {
        if (isFirst) {
          isFirst = false
        } else {
          sb.append(", ")
        }
        visitType(elem)
      }
      sb.append(">")
    }
  }

  override fun visitUnknownType(node: PklUnknownType) {
    sb.append("unknown")
  }

  override fun visitDefaultUnionType(node: PklDefaultUnionType) {
    sb.append("*")
    node.type.accept(this)
  }

  override fun visitParenthesizedType(node: PklParenthesizedType) {
    sb.append("(")
    node.type.accept(this)
    sb.append(")")
  }

  override fun visitUnionType(node: PklUnionType) {
    node.leftType.accept(this)
    sb.append(" | ")
    node.rightType.accept(this)
  }

  override fun visitFunctionType(node: PklFunctionType) {
    @Suppress("DuplicatedCode") sb.append("(")
    var isFirst = true
    for (param in node.parameterList) {
      if (isFirst) {
        isFirst = false
      } else {
        sb.append(", ")
      }
      param.accept(this)
    }
    sb.append(") -> ")
    node.returnType.accept(this)
  }

  override fun visitModuleType(node: PklModuleType) {
    val module = node.enclosingModule ?: return
    if (module == myModule) {
      sb.append("module")
    } else {
      sb.append(myModule.findOrInsertImport(module, imports, insertedImports))
    }
  }

  override fun visitNothingType(node: PklNothingType) {
    sb.append("nothing")
  }

  override fun visitNullableType(node: PklNullableType) {
    node.type.accept(this)
    sb.append("?")
  }

  override fun visitStringLiteralType(node: PklStringLiteralType) {
    sb.append(node.text)
  }

  override fun visitConstrainedType(node: PklConstrainedType) {
    val myType = node.type ?: return
    myType.accept(this)
    sb.append("(")
    var isFirst = true
    for (expr in node.exprs) {
      if (isFirst) {
        isFirst = false
      } else {
        sb.append(", ")
      }
      expr.accept(this)
    }
    sb.append(")")
  }

  override fun visitExpr(node: PklExpr) {
    // TODO this doesn't handle member access.
    sb.append(node.text)
  }

  override fun visitTypeTestExpr(node: PklTypeTestExpr) {
    node.expr?.accept(this)
    sb.append(' ')
    sb.append(node.operator.text)
    sb.append(' ')
    node.type?.accept(this)
  }

  override fun visitTypeCastExpr(node: PklTypeCastExpr) {
    node.expr?.accept(this)
    sb.append(' ')
    sb.append(node.operator.text)
    sb.append(' ')
    node.type?.accept(this)
  }
}
