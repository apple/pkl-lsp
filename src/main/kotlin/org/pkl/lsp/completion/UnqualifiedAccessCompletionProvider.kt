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

import org.eclipse.lsp4j.*
import org.pkl.lsp.*
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.resolvers.withoutShadowedElements
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.computeThisType
import org.pkl.lsp.type.inferExprTypeFromContext
import org.pkl.lsp.type.toType

class UnqualifiedAccessCompletionProvider(private val project: Project) : CompletionProvider {

  override fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
  ) {
    getCompletions(node, params, collector, reparsed = false)
  }

  private fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
    reparsed: Boolean,
  ) {
    val line = params.position.line + 1
    val column = params.position.character + 1
    val context = node.containingFile.pklProject
    val base = project.pklBaseModule
    val actualNode = node.enclosingModule?.findBySpan(line, column) ?: return
    val thisType = actualNode.computeThisType(base, mapOf(), context)

    if (thisType == Type.Unknown) return

    if (
      inClassBody(actualNode) || inObjectBody(actualNode) || inTopLevelModule(actualNode, column)
    ) {
      if (reparsed) return
      val alreadyDefinedProperties = collectPropertyNames(actualNode)
      if (
        addDefinitionCompletions(
          actualNode,
          thisType,
          alreadyDefinedProperties,
          base,
          collector,
          context,
        )
      ) {
        return
      }
    }

    if (actualNode !is PklUnqualifiedAccessExpr) {
      // user didn't type any identifier, we have to reparse
      if (reparsed) return // prevent stack overflow
      val editedSource =
        editSource(node.source, params.position.line, params.position.character, "a")
      // dispose of the newly parsed node after use
      project.pklParser.parse(editedSource).use { tsnode ->
        val mod = PklModuleImpl(tsnode, node.containingFile)
        // move the column 1 char to the right, because we added a char to the source
        val newParams =
          CompletionParams().apply {
            this.position = Position(params.position.line, params.position.character + 1)
            this.context = params.context
            this.textDocument = params.textDocument
            this.workDoneToken = params.workDoneToken
            this.partialResultToken = params.partialResultToken
          }
        getCompletions(mod, newParams, collector, reparsed = true)
      }
      return
    }

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

  private fun inClassBody(node: PklNode): Boolean =
    node is PklClassBody || (node is PklError && node.parent is PklClassBody)

  private fun inObjectBody(node: PklNode): Boolean =
    node is PklObjectBody || node.parent is PklObjectElement

  // This is a heuristic to detect if this node is a top-level definition.
  // We can't just rely upon `node is PklModule` because there are some
  // cases where it doesn't work: `function f() = <caret>`
  private fun inTopLevelModule(node: PklNode, col: Int): Boolean =
    (node is PklModule && col == 1) || (node is PklError && node.parent is PklModule)

  private fun addDefinitionCompletions(
    node: PklNode,
    thisType: Type,
    alreadyDefinedProperties: Set<String>,
    base: PklBaseModule,
    collector: MutableList<CompletionItem>,
    context: PklProject?,
  ): Boolean {

    return when {
      thisType == Type.Unknown -> false
      thisType is Type.Union ->
        addDefinitionCompletions(
          node,
          thisType.leftType,
          alreadyDefinedProperties,
          base,
          collector,
          context,
        ) &&
          addDefinitionCompletions(
            node,
            thisType.rightType,
            alreadyDefinedProperties,
            base,
            collector,
            context,
          )
      thisType.isSubtypeOf(base.typedType, base, context) -> {
        val enclosingDef =
          node.parentOfTypes(
            PklModule::class,
            PklClass::class,
            // stop classes
            PklExpr::class,
            PklObjectBody::class,
          )
        val isClassDef =
          when (enclosingDef) {
            is PklModule -> !enclosingDef.isAmend
            is PklClass -> true
            else -> false
          }
        addTypedCompletions(
          alreadyDefinedProperties,
          isClassDef,
          thisType,
          base,
          collector,
          context,
        )
        collector.addAll(DEFINITION_LEVEL_KEYWORD_ELEMENTS)

        if (node !is PklExpr && node !is PklObjectBody) {
          when (enclosingDef) {
            is PklModule -> collector.addAll(MODULE_LEVEL_KEYWORD_ELEMENTS)
            is PklClass -> collector.addAll(CLASS_LEVEL_KEYWORD_ELEMENTS)
          }
        }

        true // typed objects cannot have elements
      }
      else -> {
        val thisClassType = thisType.toClassType(base, context) ?: return false
        when {
          thisClassType.classEquals(base.mappingType) -> {
            addMappingCompletions(alreadyDefinedProperties, thisClassType, base, collector, context)
            collector.addAll(DEFINITION_LEVEL_KEYWORD_ELEMENTS)
            true // mappings cannot have elements
          }
          thisClassType.classEquals(base.listingType) -> {
            addListingCompletions(alreadyDefinedProperties, thisClassType, base, collector, context)
            collector.addAll(DEFINITION_LEVEL_KEYWORD_ELEMENTS)
            false
          }
          thisClassType.classEquals(base.dynamicType) -> {
            collector.addAll(DEFINITION_LEVEL_KEYWORD_ELEMENTS)
            false
          }
          else -> false
        }
      }
    }
  }

  private fun addTypedCompletions(
    alreadyDefinedProperties: Set<String>,
    isClassOrModule: Boolean,
    thisType: Type,
    base: PklBaseModule,
    collector: MutableList<CompletionItem>,
    context: PklProject?,
  ) {

    val properties =
      when (thisType) {
        is Type.Class -> thisType.ctx.cache(context).properties
        is Type.Module -> thisType.ctx.cache(context).properties
        else -> unexpectedType(thisType)
      }

    for ((propertyName, property) in properties) {
      if (propertyName in alreadyDefinedProperties) continue
      if (property.isFixedOrConst && !isClassOrModule) continue

      val propertyType = property.type.toType(base, thisType.bindings, context)
      val amendedPropertyType = propertyType.amended(base, context)
      if (amendedPropertyType != Type.Nothing && amendedPropertyType != Type.Unknown) {
        val amendingPropertyType = propertyType.amending(base, context)
        collector += createPropertyAmendElement(propertyName, amendingPropertyType, property)
      }
      collector += createPropertyAssignElement(propertyName, propertyType, property)
    }
  }

  private fun addMappingCompletions(
    alreadyDefinedProperties: Set<String>,
    thisType: Type.Class,
    base: PklBaseModule,
    collector: MutableList<CompletionItem>,
    context: PklProject?,
  ) {

    val keyType = thisType.typeArguments[0]
    val valueType = thisType.typeArguments[1]

    val amendedValueType = valueType.amended(base, context)
    if (amendedValueType != Type.Nothing && amendedValueType != Type.Unknown) {
      val amendingValueType = valueType.amending(base, context)
      if ("default" !in alreadyDefinedProperties) {
        collector +=
          createPropertyAmendElement("default", amendingValueType, base.mappingDefaultProperty)
      }
      collector += createEntryAmendElement(keyType, amendingValueType, base)
    }
    if ("default" !in alreadyDefinedProperties) {
      collector += createPropertyAssignElement("default", valueType, base.mappingDefaultProperty)
    }
    collector += createEntryAssignElement(keyType, valueType, base)
  }

  private fun addListingCompletions(
    alreadyDefinedProperties: Set<String>,
    thisType: Type.Class,
    base: PklBaseModule,
    collector: MutableList<CompletionItem>,
    context: PklProject?,
  ) {

    val elementType = thisType.typeArguments[0]

    val amendedElementType = elementType.amended(base, context)
    if (amendedElementType != Type.Nothing && amendedElementType != Type.Unknown) {
      val amendingElementType = elementType.amending(base, context)
      if ("default" !in alreadyDefinedProperties) {
        collector +=
          createPropertyAmendElement("default", amendingElementType, base.listingDefaultProperty)
      }
      collector += createPropertyAmendElement("new", amendingElementType, null)
    }
    if ("default" !in alreadyDefinedProperties) {
      collector += createPropertyAssignElement("default", elementType, base.listingDefaultProperty)
    }
  }

  private fun createPropertyAmendElement(
    propertyName: String,
    propertyType: Type,
    propertyCtx: PklProperty?,
  ): CompletionItem {
    return CompletionItem("$propertyName {…}").apply {
      insertTextFormat = InsertTextFormat.Snippet
      insertText =
        """
        ${propertyCtx.modifiersStr}$propertyName {
          $1
        }
        """
          .trimIndent()
      detail = propertyType.render()
      kind = CompletionItemKind.Property
    }
  }

  private fun createPropertyAssignElement(
    propertyName: String,
    propertyType: Type,
    propertyCtx: PklProperty,
  ): CompletionItem {
    return CompletionItem("$propertyName = ").apply {
      insertTextFormat = InsertTextFormat.Snippet
      insertText = "${propertyCtx.modifiersStr}$propertyName = $1"
      detail = propertyType.render()
      kind = CompletionItemKind.Property
    }
  }

  private fun createEntryAmendElement(
    keyType: Type,
    valueType: Type,
    base: PklBaseModule,
  ): CompletionItem {
    val defaultKey = createDefaultKey(keyType, base)
    return CompletionItem("[$defaultKey] {…}").apply {
      insertTextFormat = InsertTextFormat.Snippet
      insertText =
        """
        [${createDefaultKey(keyType, base, true)}] {
          $2
        }
        """
          .trimIndent()
      detail = valueType.render()
      kind = CompletionItemKind.Field
    }
  }

  private fun createEntryAssignElement(
    keyType: Type,
    valueType: Type,
    base: PklBaseModule,
  ): CompletionItem {
    val defaultKey = createDefaultKey(keyType, base)
    return CompletionItem("[$defaultKey] = ").apply {
      insertTextFormat = InsertTextFormat.Snippet
      insertText = "[${createDefaultKey(keyType, base, true)}] = $2"
      detail = valueType.render()
      kind = CompletionItemKind.Field
    }
  }

  private val PklProperty?.modifiersStr: String
    get() {
      if (this == null || !isFixed) return ""
      val self = this
      // if the parent is fixed/const, the amending element must also be declared fixed/const too.
      // although not necessary, add the hidden modifier too so that it's clear to users.
      return buildString {
        val elems = self.modifiers ?: listOf()
        for (modifier in elems) {
          append("${modifier.text} ")
        }
      }
    }

  private fun collectPropertyNames(context: PklNode): Set<String> {
    return when (
      val container = context.parentOfTypes(PklModule::class, PklClass::class, PklObjectBody::class)
    ) {
      is PklModule -> container.properties.mapTo(mutableSetOf()) { it.name }
      is PklClass -> container.properties.mapTo(mutableSetOf()) { it.name }
      is PklObjectBody -> container.properties.mapTo(mutableSetOf()) { it.name }
      else -> setOf()
    }
  }

  private fun createDefaultKey(
    keyType: Type,
    base: PklBaseModule,
    isSnippet: Boolean = false,
  ): String =
    when (keyType) {
      base.stringType -> if (isSnippet) "\"$1\"" else "\"key\""
      base.intType -> if (isSnippet) "$1" else "123"
      base.booleanType -> if (isSnippet) "$1" else "true"
      else -> if (isSnippet) "$1" else "key"
    }

  companion object {
    private val EXPRESSION_LEVEL_KEYWORD_ELEMENTS =
      listOf(
          Completion("as", "$1 as $2", "Type cast"),
          Completion("else", "else ", "Else clause"),
          Completion("false", "false", "Boolean", CompletionItemKind.Constant),
          Completion("if", "if ($1) $2 else $3", "If-then-else"),
          Completion("import", "import(\"$1\")", "Import expression"),
          Completion("import*", "import*(\"$1\")", "Import glob"),
          Completion("is", "$1 is $2", "Type check"),
          Completion("let", "let ($1 = $2) $1", "Let variable"),
          Completion("module", "module", "Module", CompletionItemKind.Constant),
          Completion("new", "new $1 {}", "New object"),
          Completion("null", "null", "Null", CompletionItemKind.Constant),
          Completion("outer", "outer", "Access outer scope", CompletionItemKind.Constant),
          Completion("read", "read($1)", "Read expression"),
          Completion("read?", "read?($1)", "Read or null"),
          Completion("read*", "read*($1)", "Read glob"),
          Completion("super", "super", "Super class", CompletionItemKind.Constant),
          Completion("this", "this", "This", CompletionItemKind.Constant),
          Completion("throw", "throw($1)", "Throw"),
          Completion("trace", "trace($1)", "Trace"),
          Completion("true", "true", "Boolean", CompletionItemKind.Constant),
        )
        .map(Completion::toCompletionItem)

    private val DEFINITION_LEVEL_KEYWORD_ELEMENTS =
      listOf(
          Completion(
            "for",
            """
            for ($1 in $2) {
              $3
            }
            """
              .trimIndent(),
            "For generator",
          ),
          Completion("function", "function $1($2) = $3", "Function"),
          Completion("hidden", "hidden ", "", CompletionItemKind.Keyword),
          Completion("local", "local ", "", CompletionItemKind.Keyword),
          Completion("fixed", "fixed ", "", CompletionItemKind.Keyword),
          Completion("const", "const ", "", CompletionItemKind.Keyword),
          Completion(
            "when",
            """
            when ($1) {
              $2
            }
            """
              .trimIndent(),
            "When generator",
          ),
        )
        .map(Completion::toCompletionItem)

    private val MODULE_LEVEL_KEYWORD_ELEMENTS =
      listOf(
          Completion("abstract", "abstract ", "", CompletionItemKind.Keyword),
          Completion("amends", "amends ", "", CompletionItemKind.Keyword),
          Completion("class", "class ", "", CompletionItemKind.Keyword),
          Completion("extends", "extends ", "", CompletionItemKind.Keyword),
          Completion("import", "import \"$1\"", "Import clause"),
          Completion("import*", "import* \"$1\"", "Import glob"),
          Completion("module", "module ", "", CompletionItemKind.Keyword),
          Completion("open", "open ", "", CompletionItemKind.Keyword),
          Completion("typealias", "typealias ", "", CompletionItemKind.Keyword),
        )
        .map(Completion::toCompletionItem)

    private val CLASS_LEVEL_KEYWORD_ELEMENTS =
      listOf(Completion("abstract", "abstract ", "", CompletionItemKind.Keyword))
        .map(Completion::toCompletionItem)

    private data class Completion(
      val label: String,
      val insert: String,
      val detail: String,
      val kind: CompletionItemKind = CompletionItemKind.Snippet,
    ) {
      fun toCompletionItem(): CompletionItem {
        return CompletionItem(label).apply {
          detail = detail
          kind = kind
          if (insert.contains("\$")) {
            insertTextFormat = InsertTextFormat.Snippet
            insertText = insert
          } else {
            insertText = insert
          }
        }
      }
    }
  }
}
