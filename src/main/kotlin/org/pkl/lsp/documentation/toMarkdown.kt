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
package org.pkl.lsp.documentation

import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.computeExprType
import org.pkl.lsp.type.computeResolvedImportType
import org.pkl.lsp.type.computeThisType

fun PklNode.toMarkdown(originalNode: PklNode?, context: PklProject?): String {
  val markdown = doRenderMarkdown(originalNode, context)
  return when {
    this is PklModule && header != null -> showDocCommentAndModule(header, markdown, context)
    else -> showDocCommentAndModule(this, markdown, context)
  }
}

fun Type.toMarkdown(project: Project, context: PklProject?): String {
  val markdown = render()
  val ctx = getNode(project, context)
  return showDocCommentAndModule((ctx as? PklModule)?.header ?: ctx, markdown, context)
}

private fun PklNode.doRenderMarkdown(originalNode: PklNode?, context: PklProject?): String =
  when (this) {
    is PklProperty ->
      buildString {
        append(modifiers.render())
        if (isLocal || isFixedOrConst) {
          append(renderTypeAnnotation(name, type, this@doRenderMarkdown, originalNode, context))
        } else {
          append(name)
          append(": ")
          append(type?.doRenderMarkdown(originalNode, context) ?: "unknown")
        }
      }
    is PklStringLiteralType -> "\"$text\""
    is PklMethod -> {
      val modifiers = modifiers.render()
      modifiers + methodHeader.doRenderMarkdown(originalNode, context)
    }
    is PklMethodHeader ->
      buildString {
        append("function ")
        append(identifier?.text ?: "<method>")
        append(typeParameterList?.doRenderMarkdown(originalNode, context) ?: "")
        append(parameterList?.doRenderMarkdown(originalNode, context) ?: "()")
        val returnTypeStr =
          if (returnType != null) {
            returnType!!.doRenderMarkdown(originalNode, context)
          } else {
            val parent = this@doRenderMarkdown.parent
            if (parent != null && parent is PklMethod) {
              val type = parent.body.computeExprType(project.pklBaseModule, mapOf(), context)
              type.render()
            } else "unknown"
          }
        append(": ")
        append(returnTypeStr)
      }
    is PklParameterList -> {
      elements.joinToString(", ", prefix = "(", postfix = ")") {
        it.doRenderMarkdown(originalNode, context)
      }
    }
    is PklTypeParameterList -> {
      typeParameters.joinToString(", ", prefix = "<", postfix = ">") {
        it.doRenderMarkdown(originalNode, context)
      }
    }
    is PklTypeAnnotation -> ": ${type!!.doRenderMarkdown(originalNode, context)}"
    is PklTypedIdentifier ->
      renderTypeAnnotation(identifier?.text, typeAnnotation?.type, this, originalNode, context)!!
    is PklTypeParameter -> {
      val vari = variance?.name?.lowercase()?.let { "$it " } ?: ""
      "$vari$name"
    }
    is PklClass ->
      buildString {
        append(modifiers.render())
        append("class ")
        append(identifier?.text ?: "<class>")
        typeParameterList?.let { append(it.doRenderMarkdown(originalNode, context)) }
        supertype?.let {
          append(" extends ")
          append(it.doRenderMarkdown(originalNode, context))
        }
      }
    is PklModule -> header?.doRenderMarkdown(originalNode, context) ?: "module $moduleName"
    is PklModuleHeader ->
      buildString {
        append(modifiers.render())
        append("module ")
        // can never be null
        moduleClause?.let { append(it.doRenderMarkdown(originalNode, context)) }
          ?: append("<module>")
        moduleExtendsAmendsClause?.let {
          append(if (it.isAmend) " amends " else " extends ")
          append(it.moduleUri!!.stringConstant.text)
        }
      }
    is PklModuleClause ->
      buildString { append(moduleName ?: enclosingModule?.moduleName ?: "<module>") }
    is PklImport ->
      buildString {
        if (isGlob) {
          append("import* ")
        } else {
          append("import ")
        }
        moduleUri?.stringConstant?.escapedText()?.let { append("\"$it\"") }
        val definitionType =
          resolve(context).computeResolvedImportType(project.pklBaseModule, mapOf(), false, context)
        append(": ")
        definitionType.render(this, DefaultTypeNameRenderer)
      }
    is PklTypeAlias ->
      buildString {
        append(modifiers.render())
        append("typealias ")
        append(identifier?.text)
        typeParameterList?.let { append(it.doRenderMarkdown(originalNode, context)) }
      }
    is PklType -> render()
    else -> text
  }

// render modifiers
private fun List<Terminal>?.render(): String {
  return this?.let { if (isEmpty()) "" else joinToString(" ", postfix = " ") { it.text } } ?: ""
}

private fun renderTypeAnnotation(
  name: String?,
  type: PklType?,
  node: PklNode,
  originalNode: PklNode?,
  context: PklProject?,
): String? {
  if (name == null) return null
  return buildString {
    append(name)
    when {
      originalNode !== node && originalNode?.isAncestor(node) == false -> {
        val visitor =
          ResolveVisitors.typeOfFirstElementNamed(
            name,
            null,
            node.project.pklBaseModule,
            isNullSafeAccess = false,
            preserveUnboundTypeVars = false,
          )
        val computedType =
          Resolvers.resolveUnqualifiedAccess(
            originalNode,
            node.computeThisType(node.project.pklBaseModule, mapOf(), context),
            true,
            node.project.pklBaseModule,
            mapOf(),
            visitor,
            context,
          )
        append(": ")
        if (computedType is Type.Unknown && type != null) {
          append(type.render())
        } else {
          computedType.render(this)
        }
      }
      type != null -> {
        append(": ")
        append(type.doRenderMarkdown(originalNode, context))
      }
      else -> {
        val computedType =
          node.computeResolvedImportType(node.project.pklBaseModule, mapOf(), context)
        append(": ")
        computedType.render(this)
      }
    }
  }
}

private fun showDocCommentAndModule(node: PklNode?, text: String, context: PklProject?): String {
  return buildString {
    append(
      """
      ```pkl
      $text
      ```
      """
        .trimIndent()
    )
    if (node is PklDocCommentOwner) {
      node.effectiveDocComment(context)?.let { comment ->
        appendLine()
        appendLine()
        append("---")
        appendLine()
        appendLine()
        append(comment)
      }
    }
    val module = (if (node is PklModule) node else node?.enclosingModule)
    if (module != null) {
      appendLine()
      appendLine()
      append("---")
      appendLine()
      appendLine()
      append("in [${module.moduleName}](${module.getLocationUri(forDocs = true)})")
    }
  }
}
