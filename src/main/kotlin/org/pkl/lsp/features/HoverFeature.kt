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
package org.pkl.lsp.features

import java.net.URI
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.*

class HoverFeature(project: Project) : Component(project) {
  fun onHover(params: HoverParams): CompletableFuture<Hover> {
    fun run(mod: PklModule?): Hover {
      if (mod == null) return Hover(listOf())
      val context = mod.containingFile.pklProject
      val line = params.position.line + 1
      val col = params.position.character + 1
      val hoverText =
        mod.findBySpan(line, col)?.let { resolveHover(it, line, col, context) }
          ?: return Hover(listOf())
      return Hover(MarkupContent("markdown", hoverText))
    }
    val uri = URI(params.textDocument.uri)
    val file =
      project.virtualFileManager.get(uri)
        ?: return CompletableFuture.completedFuture(Hover(listOf()))
    return file.getModule().thenApply(::run)
  }

  private fun resolveHover(
    originalNode: PklNode,
    line: Int,
    col: Int,
    context: PklProject?,
  ): String? {
    val node = originalNode.resolveReference(line, col, context) ?: return null
    val base = project.pklBaseModule
    if (node !== originalNode) return node.toMarkdown(originalNode, context)
    return when (node) {
      is PklProperty -> node.toMarkdown(originalNode, context)
      is PklMethod -> node.toMarkdown(originalNode, context)
      is PklMethodHeader -> {
        val name = node.identifier
        // check if hovering over the method name
        if (name != null && name.span.matches(line, col)) {
          node.parent?.toMarkdown(originalNode, context)
        } else null
      }
      is PklClass -> node.toMarkdown(originalNode, context)
      is PklClassHeader -> {
        val name = node.identifier
        // check if hovering over the class name
        if (name != null && name.span.matches(line, col)) {
          // renders the class, which contains the doc comment
          node.parent?.toMarkdown(originalNode, context)
        } else null
      }
      is PklQualifiedIdentifier ->
        when (val par = node.parent) {
          // render the module declaration
          is PklModuleHeader -> par.parent?.toMarkdown(originalNode, context)
          else -> null
        }
      is PklDeclaredType -> node.toMarkdown(originalNode, context)
      is PklModule -> node.toMarkdown(originalNode, context)
      // render the typealias which contains the doc comments
      is PklTypeAliasHeader -> node.parent?.toMarkdown(originalNode, context)
      is PklTypedIdentifier -> node.toMarkdown(originalNode, context)
      is PklThisExpr -> node.computeThisType(base, mapOf(), context).toMarkdown(context)
      is PklModuleExpr -> node.enclosingModule?.toMarkdown(originalNode, context)
      else -> null
    }
  }

  private fun PklNode.render(originalNode: PklNode?, context: PklProject?): String =
    when (this) {
      is PklProperty ->
        buildString {
          append(modifiers.render())
          if (isLocal || isFixedOrConst) {
            append(renderTypeAnnotation(name, type, this@render, originalNode, context))
          } else {
            append(name)
            append(": ")
            append(type?.render(originalNode, context) ?: "unknown")
          }
        }
      is PklStringLiteralType -> "\"$text\""
      is PklMethod -> {
        val modifiers = modifiers.render()
        modifiers + methodHeader.render(originalNode, context)
      }
      is PklMethodHeader ->
        buildString {
          append("function ")
          append(identifier?.text ?: "<method>>")
          append(typeParameterList?.render(originalNode, context) ?: "")
          append(parameterList?.render(originalNode, context) ?: "()")
          val returnTypeStr =
            if (returnType != null) {
              returnType!!.render(originalNode, context)
            } else {
              val parent = this@render.parent
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
          it.render(originalNode, context)
        }
      }
      is PklTypeParameterList -> {
        typeParameters.joinToString(", ", prefix = "<", postfix = ">") {
          it.render(originalNode, context)
        }
      }
      is PklParameter ->
        if (isUnderscore) {
          "_"
        } else {
          // cannot be null here if it's not underscore
          typedIdentifier!!.render(originalNode, context)
        }
      is PklTypeAnnotation -> ": ${type!!.render(originalNode, context)}"
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
          append(classHeader.identifier?.text ?: "<class>")
          typeParameterList?.let { append(it.render(originalNode, context)) }
          supertype?.let {
            append(" extends ")
            append(it.render(originalNode, context))
          }
        }
      is PklModule -> declaration?.render(originalNode, context) ?: "module $moduleName"
      is PklModuleDeclaration ->
        buildString {
          append(modifiers.render())
          append("module ")
          // can never be null
          append(moduleHeader!!.render(originalNode, context))
        }
      is PklModuleHeader ->
        buildString {
          append(moduleName ?: enclosingModule?.moduleName ?: "<module>")
          moduleExtendsAmendsClause?.let {
            append(if (it.isAmend) " amends " else " extends ")
            append(it.moduleUri!!.stringConstant.text)
          }
        }
      is PklImport ->
        buildString {
          if (isGlob) {
            append("import* ")
          } else {
            append("import ")
          }
          moduleUri?.stringConstant?.escapedText()?.let { append("\"$it\"") }
          val definitionType =
            resolve(context)
              .computeResolvedImportType(project.pklBaseModule, mapOf(), false, context)
          append(": ")
          definitionType.render(this, DefaultTypeNameRenderer)
        }
      is PklTypeAlias -> typeAliasHeader.render(originalNode, context)
      is PklTypeAliasHeader ->
        buildString {
          append(modifiers.render())
          append("typealias ")
          append(identifier?.text)
          typeParameterList?.let { append(it.render(originalNode, context)) }
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
              project.pklBaseModule,
              isNullSafeAccess = false,
              preserveUnboundTypeVars = false,
            )
          val computedType =
            Resolvers.resolveUnqualifiedAccess(
              originalNode,
              node.computeThisType(project.pklBaseModule, mapOf(), context),
              true,
              project.pklBaseModule,
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
          append(type.render(originalNode, context))
        }
        else -> {
          val computedType = node.computeResolvedImportType(project.pklBaseModule, mapOf(), context)
          append(": ")
          computedType.render(this)
        }
      }
    }
  }

  private fun Type.toMarkdown(context: PklProject?): String {
    val markdown = render()
    val ctx = getNode(project, context)
    return when {
      ctx is PklModule && ctx.declaration != null ->
        showDocCommentAndModule(ctx.declaration!!, markdown)
      else -> showDocCommentAndModule(ctx, markdown)
    }
  }

  private fun PklNode.toMarkdown(originalNode: PklNode?, context: PklProject?): String {
    val markdown = render(originalNode, context)
    return when {
      this is PklModule && declaration != null -> showDocCommentAndModule(declaration!!, markdown)
      else -> showDocCommentAndModule(this, markdown)
    }
  }

  private fun showDocCommentAndModule(node: PklNode?, text: String): String {
    val markdown = "```pkl\n$text\n```"
    val withDoc =
      if (node is PklDocCommentOwner) {
        node.parsedComment?.let { "$markdown\n\n---\n\n$it" } ?: markdown
      } else markdown
    val module = (if (node is PklModule) node else node?.enclosingModule)
    val footer =
      if (module != null) {
        "\n\n---\n\nin [${module.moduleName}](${module.toCommandURIString()})"
      } else ""
    return "$withDoc$footer"
  }
}
