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

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.PklLSPServer
import org.pkl.lsp.ast.*
import org.pkl.lsp.type.computeResolvedImportType
import org.pkl.lsp.type.computeThisType
import org.pkl.lsp.type.toType

class CompletionFeature(val server: PklLSPServer) {
  fun onCompletion(
    params: CompletionParams
  ): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
    fun run(mod: PklModule?): Either<List<CompletionItem>, CompletionList> {
      val pklMod =
        mod
          ?: (server.builder().lastSuccessfulBuild(params.textDocument.uri)
            ?: return Either.forLeft(listOf()))

      val line = params.position.line + 1
      val col = params.position.character - 1
      @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
      return when (params.context.triggerKind) {
        CompletionTriggerKind.Invoked -> Either.forLeft(listOf())
        CompletionTriggerKind.TriggerForIncompleteCompletions -> Either.forLeft(listOf())
        CompletionTriggerKind.TriggerCharacter -> {
          // go two position behind to find the actual node to complete
          val completions =
            pklMod.findBySpan(line, col)?.resolveCompletion(line, col) ?: return Either.forLeft(listOf())
          return Either.forLeft(completions)
        }
      }
    }
    return server.builder().runningBuild(params.textDocument.uri).thenApply(::run)
  }

  private fun Node.resolveCompletion(line: Int, col: Int): List<CompletionItem>? {
    val node = resolveReference(line, col) ?: return null
    val showTypes = parentOfType<PklNewExpr>() != null
    val module = if (this is PklModule) this else enclosingModule
    return when (node) {
      is PklSingleLineStringLiteral,
      is PklMultiLineStringLiteral,
      is SingleLineStringPart,
      is MultiLineStringPart -> PklBaseModule.instance.stringType.ctx.complete()
      is PklModule -> node.complete(showTypes, module)
      is PklClass -> node.complete(showTypes, module)
      is PklClassProperty -> node.complete(showTypes, module)
      is PklThisExpr -> {
        val base = PklBaseModule.instance
        node.computeThisType(base, mapOf()).toClassType(base)?.ctx?.complete()
      }
      is PklModuleExpr -> module?.complete(showTypes, module)
      else -> if (this !== node) node.complete(showTypes, module) else null
    }
  }

  private fun Node.complete(showTypes: Boolean, sourceModule: PklModule?): List<CompletionItem> =
    when (this) {
      is PklModule -> complete(showTypes, sourceModule)
      is PklClass -> complete()
      is PklClassProperty -> complete()
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

  private fun PklClassProperty.complete(): List<CompletionItem> {
    val base = PklBaseModule.instance
    val typ = when (val typ = type) {
      null -> {
        val res = resolve()
        if (res is PklClassProperty && res.type != null) {
          res.type!!.toType(base, mapOf())
        } else null
      }
      else -> typ.toType(base, mapOf())
    } ?: computeResolvedImportType(base, mapOf())
    val clazz = typ.toClassType(base) ?: return listOf()
    return clazz.ctx.complete()
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
    val strPars = pars.mapIndexed { index, par ->
      val name = par.typedIdentifier?.identifier?.text ?: "par"
      "\${${index + 1}:$name}"
    }.joinToString(", ")

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
