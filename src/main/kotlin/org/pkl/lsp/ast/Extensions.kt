/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.eclipse.lsp4j.TextEdit
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
import org.pkl.lsp.util.GlobResolver
import org.pkl.lsp.util.ModificationTracker
import org.pkl.parser.Lexer

val PklClass.supertype: PklType?
  get() = extends

fun PklClass.superclass(context: PklProject?): PklClass? {
  return when (val st = supertype) {
    is PklDeclaredType -> st.name.resolve(context) as? PklClass?
    is PklModuleType -> null // see PklClass.supermodule
    null ->
      when {
        isPklBaseAnyClass || this == project.pklBaseModule.typedType.ctx -> null
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
  val moduleNameText = identifierName!!
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

inline fun <reified T> PklNode.lastChildOfClass(): T? {
  return lastChildMatching { it is T } as? T
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
    identifierName ?: moduleUri?.stringConstant?.escapedText()?.let { inferImportPropertyName(it) }

fun PklStringConstant.escapedText(): String? = getEscapedTextSl()

fun PklStringLiteral.escapedText(): String? =
  when (this) {
    is PklSingleLineStringLiteral -> escapedText()
    is PklMultiLineStringLiteral -> escapedText()
  }

private fun PklSingleLineStringLiteral.escapedText(): String? =
  if (!exprs.isEmpty()) null else getEscapedTextSl()

private fun PklNode.getEscapedTextSl(): String? = buildString {
  for (terminal in terminals) {
    when (terminal.type) {
      TokenType.SLQuote,
      TokenType.SLEndQuote -> {} // ignore open/close quotes
      TokenType.SLCharacters -> append(terminal.text)
      TokenType.CharacterEscape -> appendEscape(terminal)
      else ->
        // interpolated or invalid string -> bail out
        return null
    }
  }
}

private fun PklMultiLineStringLiteral.escapedText(): String? =
  if (!exprs.isEmpty()) null
  else
    buildString {
        // assumes lines can only end in \n (or \r\n)
        val mlPrefix = terminals.dropLast(1).last().text.takeLastWhile { it != '\n' }
        var afterContinuation = false
        for ((idx, terminal) in terminals.withIndex()) {
          when (terminal.type) {
            TokenType.MLQuote,
            TokenType.MLEndQuote -> {} // ignore open/close quotes
            TokenType.MLCharacters -> {
              var text = terminal.text
              if (idx == 1) text = text.removePrefix("\n$mlPrefix") // MLQuote is idx 0
              else if (afterContinuation) {
                text = text.removePrefix(mlPrefix)
                afterContinuation = false
              }
              text = text.replace("\n$mlPrefix", "\n")
              append(text)
            }
            TokenType.CharacterEscape -> appendEscape(terminal)
            TokenType.MLNewline -> append('\n')
            TokenType.StringContinuation -> afterContinuation = true
            else ->
              // interpolated or invalid string -> bail out
              return null
          }
        }
      }
      .removeSuffix("\n")

private fun Appendable.appendEscape(terminal: Terminal) {
  val text = terminal.text
  if (text.contains("u{")) {
    val index = text.indexOf('{') + 1
    val hexString = text.substring(index, text.length - 1)
    try {
      append(Character.toChars(Integer.parseInt(hexString, 16)).concatToString())
    } catch (_: NumberFormatException) {} catch (_: IllegalArgumentException) {}
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

val PklNode.isInPklRefModule: Boolean
  get() = project.stdlib.ref != null && containingFile === project.stdlib.ref

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

inline fun <reified T : PklNode> PklNode.parentsOfType(): List<T> = parentsOfTypes(T::class)

fun PklImportBase.resolve(context: PklProject?): ModuleResolutionResult =
  if (isGlob) GlobModuleResolutionResult(moduleUri?.resolveGlob(context))
  else SimpleModuleResolutionResult(moduleUri?.resolve(context))

fun PklImportBase.resolveModules(context: PklProject?): List<PklModule> =
  resolve(context).let { result ->
    when (result) {
      is SimpleModuleResolutionResult -> result.resolved?.let(::listOf) ?: emptyList()
      else -> {
        result as GlobModuleResolutionResult
        if (result.resolved == null || result.resolved.exceededMaxElements) emptyList()
        else result.resolved.elements.mapNotNull { it.getModule().get() }
      }
    }
  }

fun PklModuleUri.resolveGlob(context: PklProject?): GlobResolver.GlobResult? {
  val text = this.stringConstant.escapedText() ?: return null
  return resolveGlob(text, text, this, context)
}

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

class GlobModuleResolutionResult(val resolved: GlobResolver.GlobResult?) :
  ModuleResolutionResult() {
  override fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
    context: PklProject?,
  ): Type {
    if (resolved == null) return Type.Unknown
    if (resolved.exceededMaxElements || resolved.elements.isEmpty())
      return base.mappingType.withTypeArguments(base.stringType, base.moduleType)
    val allTypes =
      resolved.elements.mapNotNull { elem ->
        if (elem.isDirectory) return@mapNotNull null
        val module = elem.getModule().get() ?: return@mapNotNull null
        module.computeResolvedImportType(base, bindings, context, preserveUnboundedVars)
          as Type.Module
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
        is PklClassExtendsClause -> {
          par.type.resolveReference(line, col, context)
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
  val useCommandLink = project.clientOptions.renderOpenFileCommandInDocs
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

fun <T> List<T>.withReplaced(idx: Int, elem: T): List<T> =
  toMutableList().apply { this[idx] = elem }

fun PklTypeDefOrModule.parentTypeDef(context: PklProject?): PklTypeDefOrModule? {
  return when (this) {
    is PklClass -> superclass(context) ?: supermodule(context)
    is PklModule -> supermodule(context)
    else -> null
  }
}

fun PklTypeDefOrModule.methods(context: PklProject?): Map<String, PklClassMethod>? {
  return when (val def = parentTypeDef(context)) {
    is PklClass -> def.cache(context).methods
    is PklModule -> def.cache(context).methods
    else -> null
  }
}

fun PklTypeDefOrModule.effectiveParentProperties(
  context: PklProject?
): Map<String, PklClassProperty>? {
  return when (val def = parentTypeDef(context)) {
    is PklClass -> def.cache(context).leafProperties
    is PklModule -> def.cache(context).leafProperties
    else -> null
  }
}

val PklTypeDefOrModule.declaredProperties: List<PklClassProperty>
  get() =
    when (this) {
      is PklClass -> this.properties
      is PklModule -> this.properties
      else -> emptyList()
    }

fun PklTypeDefOrModule.hasDeclaredProperty(name: String): Boolean {
  for (member in declaredProperties) {
    if (member.name == name) {
      return true
    }
  }
  return false
}

val PklTypeDefOrModule.declaredMethods: List<PklClassMethod>
  get() =
    when (this) {
      is PklClass -> this.methods
      is PklModule -> this.methods
      else -> emptyList()
    }

fun PklTypeDefOrModule.hasDeclaredMethod(name: String?): Boolean {
  if (name == null) return false
  for (member in declaredMethods) {
    if (member.name == name) {
      return true
    }
  }
  return false
}

private fun appendNumericSuffix(name: String): String =
  when (name.last()) {
    in '0'..'9' -> {
      val number = name.takeLastWhile { it in '0'..'9' }
      name.substring(0, name.length - number.length) + (number.toInt() + 1)
    }
    else -> name + '2'
  }

/**
 * Finds or inserts (in sort order) an import for [module] and returns its member name. Returns
 * `null` if this operation could not be performed (e.g., due to invalid code).
 *
 * Creates dependency-notation URIs, or constructs relative path segments if available.
 */
fun PklModule.findOrInsertImport(
  module: PklModule,
  imports: MutableList<ImportInfo>,
  textEdits: MutableList<TextEdit>,
): String {
  val defaultImportName = inferImportPropertyName(module.uri.toString()) ?: return "<>"
  var effectiveImportName: String = defaultImportName
  for (import in imports) {
    if (import.module == module) return import.presentation.memberName
  }
  val existingImportNames = imports.mapTo(mutableSetOf()) { it.presentation.memberName }
  while (existingImportNames.contains(effectiveImportName)) {
    effectiveImportName = appendNumericSuffix(effectiveImportName)
  }

  val importPath =
    when {
      uri.scheme == "package" -> {
        val myDependency =
          this.containingFile.pklProject?.myDependencies?.entries?.find {
            uri.toString().startsWith(it.value.packageUri.toString())
          }
        if (myDependency != null) {
          val pathWithinPackage = uri.fragment
          "@${myDependency.key}${pathWithinPackage}"
        } else {
          // this module comes from is a package, but the originating module doesn't declare this as
          // a dependency.
          // bail out and just import the `package://` URI
          uri.toString()
        }
      }
      else -> this.virtualFile.relativize(module.virtualFile) ?: module.uri.toString()
    }

  val importText =
    if (effectiveImportName == defaultImportName) {
      "import \"${importPath}\""
    } else {
      "import \"${importPath}\" as ${Lexer.maybeQuoteIdentifier(effectiveImportName)}"
    }

  val importPresentation =
    ImportPresentation(
      importPath,
      effectiveImportName,
      if (effectiveImportName == defaultImportName) null else effectiveImportName,
    )
  var newElementAdded = false
  for (oldImport in imports) {
    if (importPresentation < oldImport.presentation) {
      textEdits +=
        TextEdit().apply {
          newText = importText + "\n"
          range = oldImport.span.firstCaret().toRange()
        }
      imports += ImportInfo(importPresentation, module, oldImport.span.firstCaret())
      newElementAdded = true
      break
    }
  }

  if (!newElementAdded) {
    val insertAfter = this.imports.lastOrNull()?.span ?: this.header?.span
    if (insertAfter != null) {
      val span = insertAfter.firstSucceedingCaret()
      textEdits +=
        TextEdit().apply {
          range = span.toRange()
          newText = "\n" + importText
        }
      imports += ImportInfo(importPresentation, module, span)
    } else {
      val span = this.span.firstCaret()
      textEdits +=
        TextEdit().apply {
          range = span.toRange()
          newText = importText + "\n"
        }
      imports += ImportInfo(importPresentation, module, span)
    }
  }

  return effectiveImportName
}

fun PklNode.prepend(newText: String): TextEdit {
  return TextEdit().apply {
    this.range = span.firstCaret().toRange()
    this.newText = newText
  }
}

fun PklNode.append(newText: String): TextEdit {
  return TextEdit().apply {
    this.range = span.firstSucceedingCaret().toRange()
    this.newText = newText
  }
}

fun PklClassProperty.hasDefault(base: PklBaseModule, context: PklProject?): Boolean {
  return expr != null || type.toType(base, mapOf(), context).hasDefault(base, context)
}

fun StringBuilder.appendIdentifier(identifier: String) {
  append(Lexer.maybeQuoteIdentifier(identifier))
}
