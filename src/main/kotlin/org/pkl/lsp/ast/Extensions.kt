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
package org.pkl.lsp.ast

import java.net.URLEncoder
import org.pkl.lsp.*
import org.pkl.lsp.ast.PklModuleUriImpl.Companion.resolve
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.TypeParameterBindings
import org.pkl.lsp.type.computeResolvedImportType

val PklClass.supertype: PklType?
  get() = classHeader.extends

val PklClass.superclass: PklClass?
  get() {
    return when (val st = supertype) {
      is PklDeclaredType -> st.name.resolve() as? PklClass?
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
val PklClass.supermodule: PklModule?
  get() {
    return when (val st = supertype) {
      is PklDeclaredType -> st.name.resolve() as? PklModule?
      is PklModuleType -> enclosingModule
      else -> null
    }
  }

val PklClass.isPklBaseAnyClass: Boolean
  get() {
    return name == "Any" && this === project.pklBaseModule.anyType.ctx
  }

fun PklTypeName.resolve(): PklNode? = simpleTypeName.resolve()

fun PklSimpleTypeName.resolve(): PklNode? {
  val typeName = parentOfType<PklTypeName>() ?: return null

  val moduleName = typeName.moduleName
  val simpleTypeNameText = identifier?.text ?: return null
  val base = project.pklBaseModule

  if (moduleName != null) {
    return moduleName.resolve()?.cache?.types?.get(simpleTypeNameText)
  }

  return Resolvers.resolveUnqualifiedTypeName(
    this,
    base,
    mapOf(),
    ResolveVisitors.firstElementNamed(simpleTypeNameText, base),
  )
}

fun PklModuleName.resolve(): PklModule? {
  val module = enclosingModule ?: return null
  val moduleNameText = identifier!!.text
  for (import in module.imports) {
    if (import.memberName == moduleNameText) {
      val resolved = import.resolve() as? SimpleModuleResolutionResult ?: return null
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

fun PklNode.isInStdlib(): Boolean {
  return this.enclosingModule?.let { mod ->
    val uri = mod.uri
    uri.scheme == "pkl" && uri.authority == "stdlib"
  } ?: false
}

fun PklClass.isSubclassOf(other: PklClass): Boolean {
  // optimization
  if (this === other) return true

  // optimization
  if (!other.isAbstractOrOpen) return this == other

  var clazz: PklClass? = this
  while (clazz != null) {
    if (clazz == other) return true
    if (clazz.supermodule != null) {
      return project.pklBaseModule.moduleType.ctx.isSubclassOf(other)
    }
    clazz = clazz.superclass
  }
  return false
}

fun PklClass.isSubclassOf(other: PklModule): Boolean {
  // optimization
  if (!other.isAbstractOrOpen) return false

  var clazz = this
  var superclass = clazz.superclass
  while (superclass != null) {
    clazz = superclass
    superclass = superclass.superclass
  }
  var module = clazz.supermodule
  while (module != null) {
    if (module == other) return true
    module = module.supermodule
  }
  return false
}

val PklImport.memberName: String?
  get() =
    identifier?.text
      ?: moduleUri?.stringConstant?.escapedText()?.let { inferImportPropertyName(it) }

fun PklStringConstant.escapedText(): String? = getEscapedText()

fun PklSingleLineStringLiteral.escapedText(): String? =
  parts.mapNotNull { it.getEscapedText() }.joinToString("")

fun PklMultiLineStringLiteral.escapedText(): String? =
  parts.mapNotNull { it.getEscapedText() }.joinToString("")

private fun PklNode.getEscapedText(): String? = buildString {
  for (terminal in terminals) {
    when (terminal.type) {
      TokenType.SLQuote,
      TokenType.SLEndQuote,
      TokenType.MLQuote,
      TokenType.MLEndQuote -> {} // ignore open/close quotes
      TokenType.SLCharacters,
      TokenType.MLCharacters -> append(terminal.text)
      TokenType.SLCharacterEscape,
      TokenType.MLCharacterEscape -> {
        val text = terminal.text
        when (text[text.lastIndex]) {
          'n' -> append('\n')
          'r' -> append('\r')
          't' -> append('\t')
          '\\' -> append('\\')
          '"' -> append('"')
          else -> throw AssertionError("Unknown char escape: $text")
        }
      }
      TokenType.SLUnicodeEscape,
      TokenType.MLUnicodeEscape -> {
        val text = terminal.text
        val index = text.indexOf('{') + 1
        if (index != -1) {
          val hexString = text.substring(index, text.length - 1)
          try {
            append(Character.toChars(Integer.parseInt(hexString, 16)))
          } catch (ignored: NumberFormatException) {} catch (ignored: IllegalArgumentException) {}
        }
      }
      TokenType.MLNewline -> append('\n')
      else ->
        // interpolated or invalid string -> bail out
        return null
    }
  }
}

fun PklTypeAlias.isRecursive(seen: MutableSet<PklTypeAlias>): Boolean =
  !seen.add(this) || type.isRecursive(seen)

private fun PklType?.isRecursive(seen: MutableSet<PklTypeAlias>): Boolean =
  when (this) {
    is PklDeclaredType -> {
      val resolved = name.resolve()
      resolved is PklTypeAlias && resolved.isRecursive(seen)
    }
    is PklNullableType -> type.isRecursive(seen)
    is PklDefaultUnionType -> type.isRecursive(seen)
    is PklUnionType -> leftType.isRecursive(seen) || rightType.isRecursive(seen)
    is PklConstrainedType -> type.isRecursive(seen)
    is PklParenthesizedType -> type.isRecursive(seen)
    else -> false
  }

val PklNode.isInPklBaseModule: Boolean
  get() = enclosingModule?.let { it == it.project.stdlib.baseModule() } == true

interface TypeNameRenderer {
  fun render(name: PklTypeName, appendable: Appendable)

  fun render(type: Type.Class, appendable: Appendable)

  fun render(type: Type.Alias, appendable: Appendable)

  fun render(type: Type.Module, appendable: Appendable)
}

object DefaultTypeNameRenderer : TypeNameRenderer {
  override fun render(name: PklTypeName, appendable: Appendable) {
    appendable.append(name.simpleTypeName.identifier?.text)
  }

  override fun render(type: Type.Class, appendable: Appendable) {
    appendable.append(type.ctx.name)
  }

  override fun render(type: Type.Alias, appendable: Appendable) {
    appendable.append(type.ctx.name)
  }

  override fun render(type: Type.Module, appendable: Appendable) {
    appendable.append(type.referenceName)
  }
}

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

inline fun <reified T : PklNode> PklNode.parentOfType(): T? {
  return parentOfTypes(T::class)
}

fun PklImportBase.resolve(): ModuleResolutionResult =
  if (isGlob) GlobModuleResolutionResult(moduleUri?.resolveGlob() ?: emptyList())
  else SimpleModuleResolutionResult(moduleUri?.resolve())

fun PklImportBase.resolveModules(): List<PklModule> =
  resolve().let { result ->
    when (result) {
      is SimpleModuleResolutionResult -> result.resolved?.let(::listOf) ?: emptyList()
      else -> {
        result as GlobModuleResolutionResult
        result.resolved
      }
    }
  }

fun PklModuleUri.resolveGlob(): List<PklModule> = TODO("implement") // resolveModuleUriGlob(this)

fun PklModuleUri.resolve(): PklModule? =
  this.stringConstant.escapedText()?.let { text ->
    resolve(project, text, text, containingFile, enclosingModule)
  }

sealed class ModuleResolutionResult {
  abstract fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
  ): Type
}

class SimpleModuleResolutionResult(val resolved: PklModule?) : ModuleResolutionResult() {
  override fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
  ): Type {
    return resolved.computeResolvedImportType(base, bindings, preserveUnboundedVars)
  }
}

class GlobModuleResolutionResult(val resolved: List<PklModule>) : ModuleResolutionResult() {
  override fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
  ): Type {
    if (resolved.isEmpty())
      return base.mappingType.withTypeArguments(base.stringType, base.moduleType)
    val allTypes =
      resolved.map {
        it.computeResolvedImportType(base, bindings, preserveUnboundedVars) as Type.Module
      }
    val firstType = allTypes.first()
    val unifiedType =
      allTypes.drop(1).fold<Type.Module, Type>(firstType) { acc, type ->
        val currentModule = acc as? Type.Module ?: return@fold acc
        inferCommonType(base, currentModule, type)
      }
    return base.mappingType.withTypeArguments(base.stringType, unifiedType)
  }

  private fun inferCommonType(base: PklBaseModule, modA: Type.Module, modB: Type.Module): Type {
    return when {
      modA.isSubtypeOf(modB, base) -> modB
      modB.isSubtypeOf(modA, base) -> modA
      else -> {
        val superModA = modA.supermodule() ?: return base.moduleType
        val superModB = modB.supermodule() ?: return base.moduleType
        inferCommonType(base, superModA, superModB)
      }
    }
  }
}

// Resolve the reference under the cursor
fun PklNode.resolveReference(line: Int, col: Int): PklNode? {
  return when (this) {
    is PklSuperAccessExpr -> if (matches(line, col)) resolve() else null
    is PklProperty -> if (matches(line, col)) resolve() else null
    // qualified/unqualified access
    is PklReference -> resolve()
    is PklDeclaredType -> name.resolve()
    is PklImport -> {
      if (matches(line, col)) {
        when (val res = resolve()) {
          is SimpleModuleResolutionResult -> res.resolved
          is GlobModuleResolutionResult -> null // TODO: globs
        }
      } else null
    }
    is PklStringConstant ->
      when (val parent = parent) {
        is PklImportBase -> {
          when (val res = parent.resolve()) {
            is SimpleModuleResolutionResult -> res.resolved
            is GlobModuleResolutionResult -> null // TODO: globs
          }
        }
        is PklModuleExtendsAmendsClause -> parent.moduleUri?.resolve()
        else -> null
      }
    is PklQualifiedIdentifier ->
      when (val par = parent) {
        is PklDeclaredType -> {
          val mname = par.name.moduleName
          if (mname != null && mname.span.matches(line, col)) {
            mname.resolve()
          } else par.name.resolve()
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

fun PklNode.toURIString(): String {
  return when (val file = containingFile) {
    is StdlibFile -> "pkl://stdlib/${file.name}.pkl"
    !is FsFile -> {
      val uri = file.uri.toString()
      "pkl://${file.pklAuthority}/${URLEncoder.encode(uri, Charsets.UTF_8)}"
    }
    else -> file.uri.toString()
  }
}

fun PklNode.toCommandURIString(): String {
  val sp = beginningSpan()
  val params = """["${toURIString()}",${sp.beginLine},${sp.beginCol}]"""
  return "command:pkl.open.file?${URLEncoder.encode(params, Charsets.UTF_8)}"
}

// returns the span of this node, ignoring docs and annotations
fun PklNode.beginningSpan(): Span =
  when (this) {
    is PklModule -> declaration?.beginningSpan() ?: span
    is PklModuleDeclaration -> moduleHeader?.beginningSpan() ?: span
    is PklClass -> classHeader.beginningSpan()
    is PklTypeAlias -> typeAliasHeader.beginningSpan()
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
