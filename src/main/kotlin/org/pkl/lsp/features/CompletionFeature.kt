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
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.computeResolvedImportType
import org.pkl.lsp.type.computeThisType
import org.pkl.lsp.type.toType

class CompletionFeature(project: Project) : Component(project) {
  fun onCompletion(
    params: CompletionParams
  ): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
    fun run(mod: PklModule?): Either<List<CompletionItem>, CompletionList> {
      if (mod == null) {
        return Either.forLeft(emptyList())
      }
      val line = params.position.line + 1
      val col = params.position.character
      val context = mod.containingFile.pklProject

      @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
      return when (params.context.triggerKind) {
        CompletionTriggerKind.Invoked -> Either.forLeft(listOf())
        CompletionTriggerKind.TriggerForIncompleteCompletions -> Either.forLeft(listOf())
        CompletionTriggerKind.TriggerCharacter -> {
          // go two position behind to find the actual node to complete
          val completions =
            mod.findBySpan(line, col)?.resolveCompletion(line, col, context)
              ?: return Either.forLeft(listOf())
          return Either.forLeft(completions)
        }
      }
    }
    val uri = URI(params.textDocument.uri)
    val file =
      project.virtualFileManager.get(uri)
        ?: return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    return file.getModule().thenApply(::run)
  }

  private fun PklNode.resolveCompletion(
    line: Int,
    col: Int,
    context: PklProject?,
  ): List<CompletionItem>? {
    val node = resolveReference(line, col, context) ?: return null
    val showTypes = parentOfType<PklNewExpr>() != null
    val module = if (this is PklModule) this else enclosingModule
    return when (node) {
      is PklSingleLineStringLiteral,
      is PklMultiLineStringLiteral,
      is SingleLineStringPart,
      is MultiLineStringPart ->
        project.pklBaseModule.stringType.ctx.complete(showTypes, module, context)
      is PklIntLiteralExpr -> project.pklBaseModule.intType.ctx.complete(showTypes, module, context)
      is PklFloatLiteralExpr ->
        project.pklBaseModule.floatType.ctx.complete(showTypes, module, context)
      is PklTrueLiteralExpr,
      is PklFalseLiteralExpr ->
        project.pklBaseModule.booleanType.ctx.complete(showTypes, module, context)
      is PklReadExpr -> project.pklBaseModule.resourceType.ctx.complete(showTypes, module, context)
      is PklModule -> node.complete(showTypes, module)
      is PklClass -> node.complete(showTypes, module, context)
      is PklClassProperty -> node.complete(showTypes, module, context)
      is PklThisExpr -> {
        val base = project.pklBaseModule
        node
          .computeThisType(base, mapOf(), context)
          .toClassType(base, context)
          ?.ctx
          ?.complete(showTypes, module, context)
      }
      is PklModuleExpr -> module?.complete(showTypes, module)
      else -> if (this !== node) node.complete(showTypes, module, context) else null
    }
  }

  private fun PklNode.complete(
    showTypes: Boolean,
    sourceModule: PklModule?,
    context: PklProject?,
  ): List<CompletionItem> =
    when (this) {
      is PklModule -> complete(showTypes, sourceModule)
      is PklClass -> complete()
      is PklClassProperty -> complete(showTypes, sourceModule, context)
      else -> listOf()
    }

  private fun PklModule.complete(
    showTypes: Boolean,
    sourceModule: PklModule?,
  ): List<CompletionItem> =
    if (showTypes) {
      completeTypes(sourceModule)
    } else {
      completeProps(sourceModule) + completeTypes(sourceModule)
    }

  private fun PklModule.completeTypes(sourceModule: PklModule?): List<CompletionItem> {
    val sameModule = this == sourceModule
    return buildList {
      addAll(typeDefs.filter { sameModule || !it.isLocal }.map { it.toCompletionItem() })
    }
  }

  private fun PklModule.completeProps(sourceModule: PklModule?): List<CompletionItem> {
    val sameModule = this == sourceModule
    return buildList {
      addAll(properties.filter { sameModule || !it.isLocal }.map { it.toCompletionItem() })
      addAll(methods.filter { sameModule || !it.isLocal }.map { it.toCompletionItem() })
    }
  }

  private fun PklClass.complete(): List<CompletionItem> = buildList {
    addAll(properties.map { it.toCompletionItem() })
    addAll(methods.map { it.toCompletionItem() })
  }

  private fun PklClassProperty.complete(
    showTypes: Boolean,
    sourceModule: PklModule?,
    context: PklProject?,
  ): List<CompletionItem> {
    val base = project.pklBaseModule
    val typ =
      when (val typ = type) {
        null -> {
          val res = resolve(context)
          if (res is PklClassProperty && res.type != null) {
            res.type!!.toType(base, mapOf(), context)
          } else null
        }
        else -> typ.toType(base, mapOf(), context)
      } ?: computeResolvedImportType(base, mapOf(), context)
    return typ.complete(showTypes, sourceModule, context)
  }

  private fun Type.complete(
    showTypes: Boolean,
    sourceModule: PklModule?,
    context: PklProject?,
  ): List<CompletionItem> {
    return when (this) {
      is Type.Module -> ctx.complete(showTypes, sourceModule)
      is Type.Class -> ctx.complete()
      is Type.Union ->
        buildList {
          addAll(leftType.complete(showTypes, sourceModule, context))
          addAll(rightType.complete(showTypes, sourceModule, context))
        }
      is Type.Alias ->
        unaliased(project.pklBaseModule, context).complete(showTypes, sourceModule, context)
      else -> listOf()
    }
  }

  private fun PklClassProperty.toCompletionItem(): CompletionItem {
    val item = CompletionItem(name)
    item.kind = CompletionItemKind.Field
    item.detail = type?.render() ?: "unknown"
    item.documentation = getDoc(this)
    return item
  }

  private fun PklClassMethod.toCompletionItem(): CompletionItem {
    val item = CompletionItem(name)
    val pars = methodHeader.parameterList?.elements ?: listOf()
    val strPars =
      pars
        .mapIndexed { index, par ->
          val name = par.typedIdentifier?.identifier?.text ?: "par"
          "\${${index + 1}:$name}"
        }
        .joinToString(", ")

    val parTypes = pars.joinToString(", ") { it.type?.render() ?: "unknown" }
    val retType = methodHeader.returnType?.render() ?: "unknown"

    item.insertText = "$name($strPars)"
    item.insertTextFormat = InsertTextFormat.Snippet
    item.kind = CompletionItemKind.Method
    item.detail = "($parTypes) -> $retType"
    item.documentation = getDoc(this)
    return item
  }

  private fun PklTypeDef.toCompletionItem(): CompletionItem {
    val item = CompletionItem(name)
    item.kind = CompletionItemKind.Class
    item.detail =
      when (this) {
        is PklTypeAlias -> type.render()
        is PklClass -> classHeader.render()
      }
    item.documentation = getDoc(this)
    return item
  }

  private fun PklClassHeader.render(): String {
    return buildString {
      if (modifiers != null) {
        append(modifiers!!.joinToString(" ", postfix = " ") { it.text })
      }
      append(identifier?.text ?: "<class>")
      if (extends != null) {
        append(' ')
        append(extends!!.render())
      }
    }
  }

  companion object {
    private fun getDoc(node: PklDocCommentOwner): Either<String, MarkupContent> {
      return Either.forRight(MarkupContent("markdown", node.parsedComment ?: ""))
    }
  }
}
