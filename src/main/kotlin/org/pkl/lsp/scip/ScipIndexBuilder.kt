/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.scip

import java.nio.file.Path
import kotlin.io.path.relativeTo
import org.pkl.lsp.Release
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.ast.*
import scip.Scip.*

class ScipIndexBuilder(
  private val rootPath: Path,
  private val toolInfo: ToolInfo =
    ToolInfo.newBuilder().setName("pkl-lsp").setVersion(Release.version).build(),
) {
  private val documents = mutableMapOf<String, ScipDocumentBuilder>()
  private val symbolInfos = mutableMapOf<String, SymbolInformation>()

  fun addDocument(file: VirtualFile): ScipDocumentBuilder {
    val relativePath = file.path.relativeTo(rootPath).toString()
    val builder = ScipDocumentBuilder(relativePath, file)
    documents[relativePath] = builder
    return builder
  }

  fun addExternalSymbolDefinition(symbol: String, info: SymbolInformation) {
    // Always canonicalize symbols before storing
    val canonicalSymbol = ScipCanonicalizer.canonicalizeSymbol(symbol)
    symbolInfos[canonicalSymbol] = info
  }

  fun build(): Index {
    val builtDocs = documents.values.map { it.build() }

    // Remove duplicate symbols that have been duplicated in external symbols. Happens when indexing
    // multiple packages at the same time that take dependencies on each other
    val docSymbols =
      builtDocs.fold(
        mutableSetOf<String>(),
        { seen, doc -> seen.apply { addAll(doc.symbolsList.map { it.symbol }) } },
      )

    docSymbols.forEach { symbolInfos.remove(it) }

    return Index.newBuilder()
      .setMetadata(
        Metadata.newBuilder()
          .setVersion(ProtocolVersion.UnspecifiedProtocolVersion)
          .setToolInfo(toolInfo)
          .setProjectRoot("file://${rootPath}")
          .build()
      )
      .addAllDocuments(builtDocs)
      .addAllExternalSymbols(symbolInfos.values)
      .build()
  }
}

class ScipDocumentBuilder(private val relativePath: String, private val file: VirtualFile) {
  private val occurrences = mutableListOf<Occurrence>()
  private val documentSymbols = mutableMapOf<String, SymbolInformation>()

  fun addOccurrence(
    span: org.pkl.lsp.ast.Span,
    symbol: String,
    role: SymbolRole,
    kind: SymbolInformation.Kind = SymbolInformation.Kind.UnspecifiedKind,
  ) {
    val range = intArrayOf(span.beginLine - 1, span.beginCol - 1, span.endLine - 1, span.endCol - 1)

    // Always canonicalize symbols before adding occurrences
    val canonicalSymbol = ScipCanonicalizer.canonicalizeSymbol(symbol)

    occurrences.add(
      Occurrence.newBuilder()
        .addAllRange(range.toList())
        .setSymbol(canonicalSymbol)
        .setSymbolRoles(role.getNumber())
        .build()
    )
  }

  fun addSymbol(symbol: String, info: SymbolInformation) {
    // Always canonicalize symbols before storing
    val canonicalSymbol = ScipCanonicalizer.canonicalizeSymbol(symbol)
    documentSymbols[canonicalSymbol] = info
  }

  fun build(): Document {
    return Document.newBuilder()
      .setRelativePath(relativePath)
      .setLanguage("pkl")
      .setText(file.contents)
      .addAllOccurrences(occurrences.sortedBy { it.getRange(0) * 10000 + it.getRange(1) })
      .addAllSymbols(documentSymbols.values)
      .build()
  }
}

/**
 * Unified SCIP symbol canonicalization system following SCIP protocol specification. All symbol
 * formatting should go through this system to ensure consistency.
 */
object ScipCanonicalizer {
  /**
   * Canonicalize any identifier according to SCIP rules:
   * - simple-identifier: '_' | '+' | '-' | '$' | ASCII letter or digit
   * - escaped-identifier: '`' ... '`' (only when contains non-simple characters)
   * - Canonical rule: Use simple-identifier if possible, escaped-identifier only when necessary
   */
  fun canonicalizeIdentifier(name: String): String {
    if (name.isEmpty()) return name

    // Remove surrounding backticks if present (PKL escaping)
    val withoutBackticks =
      if (name.startsWith("`") && name.endsWith("`")) {
        name.substring(1, name.length - 1)
      } else {
        name
      }

    // Check if the name can be represented as a simple identifier
    // SCIP simple-identifier: '_' | '+' | '-' | '$' | ASCII letter or digit
    val isSimpleIdentifier =
      withoutBackticks.all { char ->
        char == '_' ||
          char == '+' ||
          char == '-' ||
          char == '$' ||
          (char in 'a'..'z') ||
          (char in 'A'..'Z') ||
          (char in '0'..'9')
      }

    return if (isSimpleIdentifier && withoutBackticks.isNotEmpty()) {
      // Can use simple identifier format - no backticks needed
      withoutBackticks
    } else {
      // Must use escaped identifier format with backticks
      // Escape internal backticks by doubling them
      val escapedContent = withoutBackticks.replace("`", "``")
      "`$escapedContent`"
    }
  }

  // Sealed class for descriptor types with pattern matching
  private sealed class DescriptorPattern(val predicate: (String) -> Boolean) {
    abstract fun canonicalize(descriptor: String): String
    
    data object TypeDescriptor : DescriptorPattern({ it.endsWith("#") }) {
      override fun canonicalize(descriptor: String): String {
        val name = descriptor.dropLast(1)
        return "${canonicalizeIdentifier(name)}#"
      }
    }
    
    data object TermDescriptor : DescriptorPattern({ it.endsWith(".") && !it.endsWith(").") }) {
      override fun canonicalize(descriptor: String): String {
        val name = descriptor.dropLast(1)
        return "${canonicalizeIdentifier(name)}."
      }
    }
    
    data object MethodDescriptor : DescriptorPattern({ it.endsWith(").") }) {
      override fun canonicalize(descriptor: String): String {
        val beforeParen = descriptor.substringBeforeLast("(")
        val afterParen = descriptor.substringAfterLast("(")
        return "${canonicalizeIdentifier(beforeParen)}($afterParen"
      }
    }
    
    data object NamespaceDescriptor : DescriptorPattern({ it.endsWith("/") }) {
      override fun canonicalize(descriptor: String): String {
        val name = descriptor.dropLast(1)
        return "${canonicalizeIdentifier(name)}/"
      }
    }
    
    data object MetaDescriptor : DescriptorPattern({ it.endsWith(":") }) {
      override fun canonicalize(descriptor: String): String {
        val name = descriptor.dropLast(1)
        return "${canonicalizeIdentifier(name)}:"
      }
    }
    
    data object MacroDescriptor : DescriptorPattern({ it.endsWith("!") }) {
      override fun canonicalize(descriptor: String): String {
        val name = descriptor.dropLast(1)
        return "${canonicalizeIdentifier(name)}!"
      }
    }
    
    data object TypeParameter : DescriptorPattern({ it.startsWith("[") && it.endsWith("]") }) {
      override fun canonicalize(descriptor: String): String {
        val name = descriptor.substring(1, descriptor.length - 1)
        return "[${canonicalizeIdentifier(name)}]"
      }
    }
    
    data object Parameter : DescriptorPattern({ it.startsWith("(") && it.endsWith(")") }) {
      override fun canonicalize(descriptor: String): String {
        val name = descriptor.substring(1, descriptor.length - 1)
        return "(${canonicalizeIdentifier(name)})"
      }
    }
    
    companion object {
      // Order matters - more specific patterns should come first
      val patterns = listOf(
        MethodDescriptor,  // Must come before TermDescriptor
        TypeDescriptor,
        TermDescriptor,
        NamespaceDescriptor,
        MetaDescriptor,
        MacroDescriptor,
        TypeParameter,
        Parameter
      )
    }
  }

  /**
   * Canonicalize SCIP descriptors using strategy pattern for better maintainability.
   */
  fun canonicalizeDescriptor(descriptor: String): String {
    if (descriptor.isEmpty()) return descriptor
    
    return DescriptorPattern.patterns
      .find { it.predicate(descriptor) }
      ?.canonicalize(descriptor)
      ?: descriptor // Return as-is for unknown patterns
  }

  /**
   * Canonicalize a complete SCIP symbol string. Handles both local symbols and full symbols with
   * scheme/package/descriptors.
   */
  fun canonicalizeSymbol(symbol: String): String {
    if (symbol.isEmpty()) return symbol

    return when {
      // Local symbol: "local <identifier>"
      symbol.startsWith("local ") -> {
        val localId = symbol.substring(6)
        "local ${canonicalizeIdentifier(localId)}"
      }
      // Full symbol: "<scheme> <manager> <package> <version> <descriptors>"
      else -> {
        val parts = symbol.split(" ")
        if (parts.size >= 5) {
          val scheme = parts[0]
          val manager = parts[1]
          val packageName = parts[2]
          val version = parts[3]
          val descriptors = parts.drop(4).joinToString(" ")

          // Canonicalize each descriptor part
          val canonicalDescriptors =
            descriptors
              .split(Regex("(?=[#./:\\[(!])"))
              .filter { it.isNotEmpty() }
              .joinToString("") { canonicalizeDescriptor(it) }

          "$scheme $manager $packageName $version $canonicalDescriptors"
        } else {
          symbol // Return as-is if format doesn't match expected pattern
        }
      }
    }
  }
}

class ScipSymbolFormatter(val rootPath: Path) {
  private val SCHEME = "pkl"
  private val MANAGER = "." // Use placeholder for no package manager
  private val DEFAULT_PACKAGE = "local"
  private val DEFAULT_VERSION = "0.0.0"

  /**
   * Generate proper SCIP symbol following the format: <scheme> ' ' <package> ' ' (<descriptor>)+
   * where <package> = <manager> ' ' <package-name> ' ' <version>
   */
  fun formatSymbol(
    node: PklNode,
    packageName: String = DEFAULT_PACKAGE,
    version: String = DEFAULT_VERSION,
  ): String {
    val packagePart = "$MANAGER $packageName $version"
    val descriptor = getDescriptor(node)
    return "$SCHEME $packagePart $descriptor"
  }

  /** Generate symbol with parent context for nested symbols */
  fun formatNestedSymbol(
    node: PklNode,
    parentDescriptors: String,
    packageInfo: PklNodeSymbolCreator.PackageInfo,
  ): String {
    val packagePart = "$MANAGER ${packageInfo.name} ${packageInfo.version}"
    val descriptor = getDescriptor(node)
    return "$SCHEME $packagePart $parentDescriptors$descriptor"
  }

  /**
   * Get SCIP descriptor for a node following SCIP grammar: <type> ::= <name> '#' <term> ::= <name>
   * '.' <method> ::= <name> '(' (<method-disambiguator>)? ').' <namespace> ::= <name> '/'
   */
  fun getDescriptor(node: PklNode): String =
    when (node) {
      is PklModule -> {
        val moduleName = getModuleName(node)
        "${ScipCanonicalizer.canonicalizeIdentifier(moduleName)}/" // namespace
      }
      is PklClass -> "${ScipCanonicalizer.canonicalizeIdentifier(node.name)}#" // type
      is PklTypeAlias -> "${ScipCanonicalizer.canonicalizeIdentifier(node.name)}#" // type
      is PklClassMethod -> "${ScipCanonicalizer.canonicalizeIdentifier(node.name)}()." // method
      is PklObjectMethod -> "${ScipCanonicalizer.canonicalizeIdentifier(node.name)}()." // method
      is PklClassProperty -> "${ScipCanonicalizer.canonicalizeIdentifier(node.name)}." // term
      is PklObjectProperty -> "${ScipCanonicalizer.canonicalizeIdentifier(node.name)}." // term
      else -> "${getNodeName(node)}." // default to term
    }

  // Module names aren't unique identifiers, so we derive a name from the URI instead
  private fun getModuleName(module: PklModule): String {
    // Special handling for stdlib
    if (module.uri.toString().startsWith("pkl:")) {
      return module.moduleName!!
    }

    val filePath = module.containingFile.path.toString()
    val projectDir = module.containingFile.pklProjectDir?.path?.toString()

    return if (projectDir != null) {
      // Module is part of a project directory
      filePath.removePrefix(projectDir).removePrefix("/")
    } else {
      // Project Dir can be null. Use the rootPath as fallback
      val rootPathStr = rootPath.toString()
      if (filePath.startsWith(rootPathStr)) {
        filePath.removePrefix(rootPathStr).removePrefix("/")
      } else {
        // If file is not under root path just return file path. It's likely part of a package
        filePath.removePrefix("/")
      }
    }
  }

  private fun getNodeName(node: PklNode): String =
    when (node) {
      is PklNamedNode -> ScipCanonicalizer.canonicalizeIdentifier(node.name)
      else ->
        ScipCanonicalizer.canonicalizeIdentifier(node.text.take(50).replace(Regex("\\s+"), "_"))
    }

  /**
   * Generate local symbol for document-local entities. All symbols are automatically canonicalized.
   */
  fun formatLocalSymbol(name: String, span: Span): String {
    val canonicalId = ScipCanonicalizer.canonicalizeIdentifier("${name}_${span.beginLine}")
    return "local $canonicalId"
  }

  /**
   * Generate external symbol reference (for built-in types, stdlib, etc.) All symbols are
   * automatically canonicalized.
   */
  fun formatExternalSymbol(
    scheme: String,
    manager: String,
    packageName: String,
    version: String,
    descriptor: String,
  ): String {
    // Apply unified canonicalization to the descriptor part
    val canonicalDescriptor = ScipCanonicalizer.canonicalizeDescriptor(descriptor)
    return "$scheme $manager $packageName $version $canonicalDescriptor"
  }

  fun getSymbolKind(node: PklNode): SymbolInformation.Kind =
    when (node) {
      is PklModule -> SymbolInformation.Kind.Module
      is PklClass -> SymbolInformation.Kind.Class
      is PklClassMethod -> SymbolInformation.Kind.Method
      is PklClassProperty -> SymbolInformation.Kind.Field
      is PklTypeAlias -> SymbolInformation.Kind.Type
      is PklObjectProperty -> SymbolInformation.Kind.Field
      is PklObjectMethod -> SymbolInformation.Kind.Method
      else -> SymbolInformation.Kind.UnspecifiedKind
    }
}
