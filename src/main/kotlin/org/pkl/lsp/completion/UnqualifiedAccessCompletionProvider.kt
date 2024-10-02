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
package org.pkl.lsp.completion

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.InsertTextFormat
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.decapitalized
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.resolvers.withoutShadowedElements
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.computeThisType
import org.pkl.lsp.type.inferExprTypeFromContext

class UnqualifiedAccessCompletionProvider(private val project: Project) : CompletionProvider {

  override fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
  ) {
    val line = params.position.line + 1
    val column = params.position.character + 1
    val context = node.containingFile.pklProject
    val base = project.pklBaseModule
    val actualNode =
      node.enclosingModule?.findBySpan(line, column) as? PklUnqualifiedAccessExpr ?: return
    val thisType = node.computeThisType(base, mapOf(), context)

    if (thisType == Type.Unknown) return

    addInferredExprTypeCompletions(node, base, collector, context)

    val visitor = ResolveVisitors.completionItems(base).withoutShadowedElements()

    val allowClasses = shouldCompleteClassesOrTypeAliases(node, base, context)
    Resolvers.resolveUnqualifiedAccess(
      actualNode,
      thisType,
      isProperty = true,
      allowClasses,
      base,
      thisType.bindings,
      visitor,
      context,
    )
    Resolvers.resolveUnqualifiedAccess(
      actualNode,
      thisType,
      isProperty = false,
      allowClasses,
      base,
      thisType.bindings,
      visitor,
      context,
    )
    collector.addAll(visitor.result)
    collector.addAll(EXPRESSION_LEVEL_KEYWORD_ELEMENTS)
  }

  private fun addInferredExprTypeCompletions(
    node: PklNode,
    base: PklBaseModule,
    result: MutableList<CompletionItem>,
    context: PklProject?,
  ) {
    val expr =
      node.parentOfTypes(PklUnqualifiedAccessExpr::class, /* stop */ PklProperty::class)
        as? PklUnqualifiedAccessExpr ?: return

    doAddInferredExprTypeCompletions(
      expr.inferExprTypeFromContext(base, mapOf(), context, false),
      { expr.inferExprTypeFromContext(base, mapOf(), context, true) },
      base,
      result,
      context,
    )
  }

  private fun doAddInferredExprTypeCompletions(
    // example: `(Key) -> Value` (used to infer parameter name suggestions `key` and `value`)
    genericType: Type,
    // example: `(String) -> Int` (used as code completion element's display type)
    actualType: () -> Type,
    base: PklBaseModule,
    result: MutableList<CompletionItem>,
    context: PklProject?,
  ) {
    val unaliasedGenericType = genericType.unaliased(base, context)

    when {
      // e.g., `(Key) -> Value` or `Function1<Key, Value>`
      unaliasedGenericType is Type.Class && unaliasedGenericType.isFunctionType -> {
        val parameterTypes = unaliasedGenericType.typeArguments.dropLast(1)
        val parameterNames = getLambdaParameterNames(parameterTypes, base)
        result += createFunctionLiteralCompletion(actualType.invoke(), parameterNames)
      }
      // e.g., `((Key) -> Value)|((Int, Key) -> Value)`
      unaliasedGenericType is Type.Union -> {
        val unaliasedActualType by lazy {
          actualType.invoke().unaliased(base, context) as Type.Union
        }
        doAddInferredExprTypeCompletions(
          unaliasedGenericType.leftType,
          { unaliasedActualType.leftType },
          base,
          result,
          context,
        )
        doAddInferredExprTypeCompletions(
          unaliasedGenericType.rightType,
          { unaliasedActualType.rightType },
          base,
          result,
          context,
        )
      }
      else -> return
    }
  }

  private fun createFunctionLiteralCompletion(
    functionType: Type,
    parameterNames: List<String>,
  ): CompletionItem {
    val text = "(${parameterNames.joinToString(", ")}) ->"
    val item = CompletionItem("$text …")
    item.detail = functionType.render()
    item.insertTextFormat = InsertTextFormat.Snippet
    val pars = parameterNames.mapIndexed { i, str -> "\${${i + 1}:$str}" }.joinToString(", ")
    item.insertText = "($pars) -> \${${parameterNames.size + 1}:body}"
    return item
  }

  private fun getLambdaParameterNames(
    parameterTypes: List<Type>,
    base: PklBaseModule,
  ): List<String> {
    assert(parameterTypes.size <= 5)

    val result = mutableListOf<String>()
    var nextIntParam = 'i'
    val nameCounts = mutableMapOf<String, Int>()

    fun addName(name: String) {
      val count = nameCounts[name]
      when {
        count == null -> {
          nameCounts[name] = -result.size // store (inverse of) index of first occurrence
          result.add(name)
        }
        count <= 0 -> { // name has one occurrence at index -count
          nameCounts[name] = 2
          result[-count] = "${name}1" // rename first occurrence
          result.add("${name}2")
        }
        else -> {
          nameCounts[name] = count + 1
          result.add("${name}${count + 1}")
        }
      }
    }

    for (paramType in parameterTypes) {
      val paramName =
        when (paramType) {
          is Type.Class ->
            if (paramType == base.intType) {
              // won't run out of these because lambda has at most 5 parameters
              (nextIntParam++).toString()
            } else {
              paramType.ctx.name.decapitalized() ?: "param"
            }
          is Type.Module -> paramType.referenceName.decapitalized()
          is Type.Alias -> paramType.ctx.name.decapitalized() ?: "param"
          is Type.Variable -> paramType.ctx.name.decapitalized() ?: "param"
          else -> "param"
        }
      addName(paramName)
    }

    return result
  }

  private fun shouldCompleteClassesOrTypeAliases(
    node: PklNode,
    base: PklBaseModule,
    context: PklProject?,
  ): Boolean {
    fun isClassOrTypeAlias(type: Type): Boolean =
      when (type) {
        is Type.Class -> type.classEquals(base.classType) || type.classEquals(base.typeAliasType)
        is Type.Alias -> isClassOrTypeAlias(type.unaliased(base, context))
        is Type.Union -> isClassOrTypeAlias(type.leftType) || isClassOrTypeAlias(type.rightType)
        else -> false
      }
    val expr = node.parentOfType<PklExpr>() ?: return false
    val type = expr.inferExprTypeFromContext(base, mapOf(), context)
    return isClassOrTypeAlias(type)
  }

  companion object {
    private val EXPRESSION_LEVEL_KEYWORD_ELEMENTS =
      listOf(
          ExprCompletion("as", "$1 as $2", "Type cast"),
          ExprCompletion("else", "else ", "Else clause"),
          ExprCompletion("false", "false", "Boolean", CompletionItemKind.Constant),
          ExprCompletion("if", "if ($1) $2 else $3", "If-then-else"),
          ExprCompletion("import", "import(\"$1\")", "Import expression"),
          ExprCompletion("import*", "import*(\"$1\")", "Import glob"),
          ExprCompletion("is", "$1 is $2", "Type check"),
          ExprCompletion("let", "let ($1 = $2) $1", "Let variable"),
          ExprCompletion("module", "module", "Module", CompletionItemKind.Constant),
          ExprCompletion("new", "new $1 {}", "New object"),
          ExprCompletion("null", "null", "Null", CompletionItemKind.Constant),
          ExprCompletion("outer", "outer", "Access outer scope", CompletionItemKind.Constant),
          ExprCompletion("read", "read($1)", "Read expression"),
          ExprCompletion("read?", "read?($1)", "Read or null"),
          ExprCompletion("read*", "read*($1)", "Read glob"),
          ExprCompletion("super", "super", "Super class", CompletionItemKind.Constant),
          ExprCompletion("this", "this", "This", CompletionItemKind.Constant),
          ExprCompletion("throw", "throw($1)", "Throw"),
          ExprCompletion("trace", "trace($1)", "Trace"),
          ExprCompletion("true", "true", "Boolean", CompletionItemKind.Constant),
        )
        .map { (label, insert, detail, kind) ->
          val item = CompletionItem(label)
          item.detail = detail
          item.kind = kind
          if (insert.contains("\$")) {
            item.insertTextFormat = InsertTextFormat.Snippet
            item.insertText = insert
          } else {
            item.insertText = insert
          }
          item
        }

    private data class ExprCompletion(
      val label: String,
      val insertText: String,
      val detail: String,
      val kind: CompletionItemKind = CompletionItemKind.Snippet,
    )
  }
}
