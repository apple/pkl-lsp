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
package org.pkl.lsp.ast

import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.pkl.lsp.*
import org.pkl.lsp.FsFile
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.StdlibFile
import org.pkl.lsp.ast.PklModuleUriImpl.Companion.resolve
import org.pkl.lsp.ast.PklModuleUriImpl.Companion.resolveGlob
import org.pkl.lsp.documentation.DefaultTypeNameRenderer
import org.pkl.lsp.documentation.TypeNameRenderer
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.TypeParameterBindings
import org.pkl.lsp.type.computeResolvedImportType
import org.pkl.lsp.type.toType
import org.pkl.lsp.util.ModificationTracker

val PklClass.supertype: PklType?
  get() = extends

fun PklClass.superclass(context: PklProject?): PklClass? {
  return when (val st = supertype) {
    is PklDeclaredType -> st.name.resolve(context) as? PklClass?
    is PklModuleType -> null // see PklClass.supermodule
    null ->
      when {
        isPklBaseAnyClass -> null
        else -> project.pklBaseModule.typedType.ctx
      }
    else -> unexpectedType(st)
  }
}

// Non-null when [this] extends a module (class).
// Ideally, [Clazz.superclass] would cover this case,
// but we don't have a common abstraction for Clazz and PklModule(Class),
// and it seems challenging to introduce one.
fun PklClass.supermodule(context: PklProject?): PklModule? {
  return when (val st = supertype) {
    is PklDeclaredType -> st.name.resolve(context) as? PklModule?
    is PklModuleType -> enclosingModule
    else -> null
  }
}

val PklClass.isPklBaseAnyClass: Boolean
  get() {
    return name == "Any" && this === project.pklBaseModule.anyType.ctx
  }

fun PklTypeName.resolve(context: PklProject?): PklNode? = simpleTypeName.resolve(context)

fun PklSimpleTypeName.resolve(context: PklProject?): PklNode? {
  val typeName = parentOfType<PklTypeName>() ?: return null

  val moduleName = typeName.moduleName
  val simpleTypeNameText = identifier?.text ?: return null
  val base = project.pklBaseModule

  if (moduleName != null) {
    return moduleName.resolve(context)?.cache(context)?.types?.get(simpleTypeNameText)
  }

  return Resolvers.resolveUnqualifiedTypeName(
    this,
    base,
    mapOf(),
    ResolveVisitors.firstElementNamed(simpleTypeNameText, base),
    context,
  )
}

fun PklModuleName.resolve(context: PklProject?): PklModule? {
  val module = enclosingModule ?: return null
  val moduleNameText = identifier!!.text
  for (import in module.imports) {
    if (import.memberName == moduleNameText) {
      val resolved = import.resolve(context) as? SimpleModuleResolutionResult ?: return null
      return resolved.resolved
    }
  }
  return null
}

fun PklNode.isAncestor(of: PklNode): Boolean {
  var node = of.parent
  while (node != null) {
    if (this == node) return true
    node = node.parent
  }
  return false
}

inline fun PklNode.lastChildMatching(predicate: (PklNode) -> Boolean): PklNode? {
  for (i in children.lastIndex downTo 0) {
    val node = children[i]
    if (predicate(node)) {
      return node
    }
  }
  return null
}

fun PklNode.firstTerminalOfType(type: TokenType): Terminal? {
  return children.find { it is Terminal && it.type == type } as Terminal?
}

fun PklNode.lastTerminalOfType(type: TokenType): Terminal? {
  return lastChildMatching { it is Terminal && it.type == type } as Terminal?
}

val PklNode.isInStdlib
  get(): Boolean = this.containingFile is StdlibFile

fun PklClass.isSubclassOf(other: PklClass, context: PklProject?): Boolean {
  // optimization
  if (this === other) return true

  var clazz: PklClass? = this
  while (clazz != null) {
    if (clazz == other) return true
    if (clazz.supermodule(context) != null) {
      return project.pklBaseModule.moduleType.ctx.isSubclassOf(other, context)
    }
    clazz = clazz.superclass(context)
  }
  return false
}

fun PklClass.isSubclassOf(other: PklModule, context: PklProject?): Boolean {
  // optimization
  if (!other.isAbstractOrOpen) return false

  var clazz = this
  var superclass = clazz.superclass(context)
  while (superclass != null) {
    clazz = superclass
    superclass = superclass.superclass(context)
  }
  var module = clazz.supermodule(context)
  while (module != null) {
    if (module == other) return true
    module = module.supermodule(context)
  }
  return false
}

/** Assumes `!this.isSubclassOf(other)`. */
fun PklClass.hasCommonSubclassWith(other: PklClass, context: PklProject?): Boolean =
  other.isSubclassOf(this, context)

val PklImport.memberName: String?
  get() =
    identifier?.text
      ?: moduleUri?.stringConstant?.escapedText()?.let { inferImportPropertyName(it) }

fun PklStringConstant.escapedText(): String? = getEscapedText()

fun PklStringLiteral.escapedText(): String? =
  when (this) {
    is PklSingleLineStringLiteral -> escapedText()
    is PklMultiLineStringLiteral -> escapedText()
  }

fun PklSingleLineStringLiteral.escapedText(): String? =
  if (exprs.isEmpty()) getEscapedText() else null

fun PklMultiLineStringLiteral.escapedText(): String? =
  if (exprs.isEmpty()) getEscapedText() else null

private fun PklNode.getEscapedText(): String? = buildString {
  for (terminal in terminals) {
    when (terminal.type) {
      TokenType.SLQuote,
      TokenType.SLEndQuote,
      TokenType.MLQuote,
      TokenType.MLEndQuote -> {} // ignore open/close quotes
      TokenType.SLCharacters,
      TokenType.MLCharacters -> append(terminal.text)
      TokenType.CharacterEscape -> {
        val text = terminal.text
        if (text.contains("u{")) {
          val index = text.indexOf('{') + 1
          val hexString = text.substring(index, text.length - 1)
          try {
            append(Character.toChars(Integer.parseInt(hexString, 16)))
          } catch (ignored: NumberFormatException) {} catch (ignored: IllegalArgumentException) {}
        } else {
          when (text[text.lastIndex]) {
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            '\\' -> append('\\')
            '"' -> append('"')
            else -> throw AssertionError("Unknown char escape: $text")
          }
        }
      }
      TokenType.MLNewline -> append('\n')
      else ->
        // interpolated or invalid string -> bail out
        return null
    }
  }
}

fun PklTypeAlias.isRecursive(seen: Set<PklTypeAlias>, context: PklProject?): Boolean =
  seen.contains(this) || type.isRecursive(seen + this, context)

private fun PklType?.isRecursive(seen: Set<PklTypeAlias>, context: PklProject?): Boolean =
  when (this) {
    is PklDeclaredType -> {
      val resolved = name.resolve(context)
      resolved is PklTypeAlias && resolved.isRecursive(seen, context)
    }
    is PklNullableType -> type.isRecursive(seen, context)
    is PklDefaultUnionType -> type.isRecursive(seen, context)
    is PklUnionType -> leftType.isRecursive(seen, context) || rightType.isRecursive(seen, context)
    is PklConstrainedType -> type.isRecursive(seen, context)
    is PklParenthesizedType -> type.isRecursive(seen, context)
    else -> false
  }

val PklNode.isInPklBaseModule: Boolean
  get() = containingFile === project.stdlib.base

val PklModuleMember.owner: PklTypeDefOrModule?
  get() = parentOfTypes(PklClass::class, PklModule::class)

val PklMethod.isOverridable: Boolean
  get() =
    when {
      isLocal -> false
      isAbstract -> true
      this is PklObjectMethod -> false
      this is PklClassMethod -> owner?.isAbstractOrOpen ?: false
      else -> unexpectedType(this)
    }

fun PklMethod.isVarArgs(base: PklBaseModule): Boolean {
  val varArgsType = base.varArgsType
  val lastParam = methodHeader.parameterList?.elements?.lastOrNull() ?: return false
  val lastParamType =
    // optimization: varargs is only available in stdlib, no need to provide context.
    lastParam.type.toType(base, mapOf(), null).toClassType(base, null)
  return lastParamType != null && lastParamType.classEquals(varArgsType)
}

inline fun <reified T : PklNode> PklNode.parentOfType(): T? {
  return parentOfTypes(T::class)
}

fun PklImportBase.resolve(context: PklProject?): ModuleResolutionResult =
  if (isGlob) GlobModuleResolutionResult(moduleUri?.resolveGlob(context) ?: emptyList())
  else SimpleModuleResolutionResult(moduleUri?.resolve(context))

fun PklImportBase.resolveModules(context: PklProject?): List<PklModule> =
  resolve(context).let { result ->
    when (result) {
      is SimpleModuleResolutionResult -> result.resolved?.let(::listOf) ?: emptyList()
      else -> {
        result as GlobModuleResolutionResult
        result.resolved
      }
    }
  }

fun PklModuleUri.resolveGlob(context: PklProject?): List<PklModule> =
  this.stringConstant.escapedText()?.let { text ->
    val futures =
      resolveGlob(text, text, this, context)?.filter { !it.isDirectory }?.map { it.getModule() }
        ?: return@let emptyList<PklModule>()
    futures.sequence().get().filterNotNull()
  } ?: emptyList()

fun PklModuleUri.resolve(context: PklProject?): PklModule? =
  this.stringConstant.escapedText()?.let { text ->
    resolve(project, text, text, containingFile, enclosingModule, context)
  }

sealed class ModuleResolutionResult {
  abstract fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
    context: PklProject?,
  ): Type
}

class SimpleModuleResolutionResult(val resolved: PklModule?) : ModuleResolutionResult() {
  override fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
    context: PklProject?,
  ): Type {
    return resolved.computeResolvedImportType(base, bindings, context, preserveUnboundedVars)
  }
}

class GlobModuleResolutionResult(val resolved: List<PklModule>) : ModuleResolutionResult() {
  override fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
    context: PklProject?,
  ): Type {
    if (resolved.isEmpty())
      return base.mappingType.withTypeArguments(base.stringType, base.moduleType)
    val allTypes =
      resolved.map {
        it.computeResolvedImportType(base, bindings, context, preserveUnboundedVars) as Type.Module
      }
    val firstType = allTypes.first()
    val unifiedType =
      allTypes.drop(1).fold<Type.Module, Type>(firstType) { acc, type ->
        val currentModule = acc as? Type.Module ?: return@fold acc
        inferCommonType(base, currentModule, type, context)
      }
    return base.mappingType.withTypeArguments(base.stringType, unifiedType)
  }

  private fun inferCommonType(
    base: PklBaseModule,
    modA: Type.Module,
    modB: Type.Module,
    context: PklProject?,
  ): Type {
    return when {
      modA.isSubtypeOf(modB, base, context) -> modB
      modB.isSubtypeOf(modA, base, context) -> modA
      else -> {
        val superModA = modA.supermodule(context) ?: return base.moduleType
        val superModB = modB.supermodule(context) ?: return base.moduleType
        inferCommonType(base, superModA, superModB, context)
      }
    }
  }
}

// Resolve the reference under the cursor
fun PklNode.resolveReference(line: Int, col: Int, context: PklProject?): PklNode? {
  return when (this) {
    is PklSuperAccessExpr -> if (matches(line, col)) resolve(context) else null
    is PklProperty -> if (matches(line, col)) resolve(context) else null
    // qualified/unqualified access
    is PklReference -> resolve(context)
    is PklDeclaredType -> name.resolve(context)
    is PklImport -> {
      if (matches(line, col)) {
        when (val res = resolve(context)) {
          is SimpleModuleResolutionResult -> res.resolved
          is GlobModuleResolutionResult -> null // TODO: globs
        }
      } else null
    }
    is PklStringConstant ->
      when (val parent = parent) {
        is PklImportBase -> {
          when (val res = parent.resolve(context)) {
            is SimpleModuleResolutionResult -> res.resolved
            is GlobModuleResolutionResult -> null // TODO: globs
          }
        }
        is PklModuleExtendsAmendsClause -> parent.moduleUri?.resolve(context)
        else -> null
      }
    is PklQualifiedIdentifier ->
      when (val par = parent) {
        is PklDeclaredType -> {
          val mname = par.name.moduleName
          if (mname != null && mname.span.matches(line, col)) {
            mname.resolve(context)
          } else par.name.resolve(context)
        }
        else -> this
      }
    else -> this
  }
}

/** Find the deepest node that matches [line] and [col]. */
fun PklNode.findBySpan(line: Int, col: Int, includeTerminals: Boolean = false): PklNode? {
  if (!includeTerminals && this is Terminal) return null
  val hit = if (span.matches(line, col)) this else null
  val childHit = children.firstNotNullOfOrNull { it.findBySpan(line, col) }
  return childHit ?: hit
}

val VirtualFile.lspUri
  get(): URI {
    return when (this) {
      is StdlibFile -> URI("pkl-lsp://stdlib/${name}.pkl")
      is FsFile -> uri
      else -> {
        val uri = uri.toString()
        URI("pkl-lsp://${pklAuthority}/${URLEncoder.encode(uri, Charsets.UTF_8)}")
      }
    }
  }

val PklNode.location: Location
  get() = Location(getLocationUri(forDocs = false).toString(), beginningSpan().toRange())

fun PklNode.locationLink(fromSpan: Span): LocationLink {
  val range = beginningSpan().toRange()
  return LocationLink(getLocationUri(forDocs = false).toString(), range, range, fromSpan.toRange())
}

fun PklNode.getLocationUri(forDocs: Boolean): URI {
  val useCommandLink = project.clientOptions.renderOpenFileCommandInDocs ?: false
  val span = beginningSpan()
  return when {
    useCommandLink && forDocs -> {
      val params = buildJsonArray {
        add(containingFile.lspUri.toString())
        add(span.beginLine)
        add(span.beginCol)
      }
      URI("command", "pkl.open.file?$params", null)
    }
    forDocs -> {
      val fragment = "L${span.beginLine},C${span.beginCol}"
      URI("${containingFile.lspUri}#$fragment")
    }
    else -> containingFile.lspUri
  }
}

// returns the span of this node, ignoring docs and annotations
fun PklNode.beginningSpan(): Span =
  when (this) {
    is PklModule -> header?.beginningSpan() ?: span
    is PklModuleHeader -> moduleClause?.beginningSpan() ?: span
    is PklClass -> identifier!!.span
    is PklTypeAlias -> identifier!!.span
    is PklClassMethod -> methodHeader.beginningSpan()
    is PklClassProperty -> {
      val mods = modifiers
      when {
        !mods.isNullOrEmpty() -> mods[0].beginningSpan()
        else -> identifier?.beginningSpan() ?: span
      }
    }
    else -> span
  }

fun PklNode.modificationTracker(): ModificationTracker = containingFile

val PklNode.isInPackage: Boolean
  get() = containingFile.`package` != null

fun PklStringConstant.contentsSpan(): Span {
  val stringStart = terminals.first().span
  val stringEnd = terminals.last().span
  return Span(stringStart.endLine, stringStart.endCol, stringEnd.beginLine, stringEnd.beginCol)
}

fun PklStringLiteral.contentsSpan(): Span {
  val stringStart = terminals.first().span
  val stringEnd = terminals.last().span
  return Span(stringStart.endLine, stringStart.endCol, stringEnd.beginLine, stringEnd.beginCol)
}

inline fun PklClass.eachSuperclassOrModule(
  context: PklProject?,
  consumer: (PklTypeDefOrModule) -> Unit,
) {
  var clazz = superclass(context)
  var supermostClass = this

  while (clazz != null) {
    consumer(clazz)
    supermostClass = clazz
    clazz = clazz.superclass(context)
  }

  var module = supermostClass.supermodule(context)
  while (module != null) {
    consumer(module)
    module = module.supermodule(context)
    if (module == null) {
      val base = project.pklBaseModule
      consumer(base.moduleType.ctx)
      consumer(base.anyType.ctx)
    }
  }
}

fun Appendable.renderTypedIdentifier(
  typedIdentifier: PklTypedIdentifier,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer,
): Appendable {
  if (typedIdentifier.isUnderscore) {
    append("_")
  } else {
    append(typedIdentifier.identifier?.text)
    renderTypeAnnotation(typedIdentifier.type, bindings, nameRenderer)
  }
  return this
}

fun Appendable.renderTypeAnnotation(
  type: PklType?,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer,
): Appendable {
  append(": ")
  renderType(type, bindings, nameRenderer)
  return this
}

fun Appendable.renderType(
  type: PklType?,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer,
): Appendable {
  when (type) {
    null -> append("unknown")
    is PklDeclaredType -> {
      val name = type.name.simpleTypeName.text
      for ((key, value) in bindings) {
        if (key.name == name) {
          value.render(this, nameRenderer)
          return this
        }
      }
      nameRenderer.render(type.name, this)
      val typeArgumentList = type.typeArgumentList
      if (typeArgumentList != null && typeArgumentList.types.any { it !is PklUnknownType }) {
        append('<')
        var first = true
        for (arg in typeArgumentList.types) {
          if (first) first = false else append(", ")
          renderType(arg, bindings, nameRenderer)
        }
        append('>')
      }
    }
    is PklNullableType -> {
      val addParens = type is PklUnionType || type is PklFunctionType
      if (addParens) append('(')
      renderType(type.type, bindings, nameRenderer)
      if (addParens) append(')')
      append('?')
    }
    is PklConstrainedType -> renderType(type.type, bindings, nameRenderer)
    is PklParenthesizedType -> {
      append('(')
      renderType(type.type, bindings, nameRenderer)
      append(')')
    }
    is PklFunctionType -> {
      append('(')
      var first = true
      for (t in type.parameterList) {
        if (first) first = false else append(", ")
        renderType(t, bindings, nameRenderer)
      }
      append(") -> ")
      renderType(type.returnType, bindings, nameRenderer)
    }
    is PklUnionType -> {
      renderType(type.leftType, bindings, nameRenderer)
      append("|")
      renderType(type.rightType, bindings, nameRenderer)
    }
    is PklDefaultUnionType -> {
      append('*')
      renderType(type.type, bindings, nameRenderer)
    }
    is PklStringLiteralType -> append(type.stringConstant.text)
    is PklNothingType -> append("nothing")
    is PklModuleType -> append("module")
    is PklUnknownType -> append("unknown")
  }

  return this
}

fun Appendable.renderParameterList(
  parameterList: PklParameterList?,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer,
): Appendable {
  append('(')

  if (parameterList != null) {
    var first = true
    for (parameter in parameterList.elements) {
      if (first) first = false else append(", ")
      renderTypedIdentifier(parameter, bindings, nameRenderer)
    }
  }

  append(')')

  return this
}
