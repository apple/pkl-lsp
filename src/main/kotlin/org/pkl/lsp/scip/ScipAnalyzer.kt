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
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PackageUri
import org.pkl.lsp.packages.dto.PklProject
import scip.Scip.*

class ScipAnalyzer(project: Project, rootPath: Path) : Component(project) {

  private val indexBuilder = ScipIndexBuilder(rootPath)
  private val symbolFormatter = ScipSymbolFormatter(rootPath)
  private val UNKNOWN_PACKAGE = PackageInfo(".", ".")

  data class PackageInfo(val name: String, val version: String)

  private fun getPackageInfo(packageUri: PackageUri?): PackageInfo =
    if (packageUri != null) {
      val packageName = packageUri.toString().substringBeforeLast("@")
      val packageVersion = packageUri?.version.toString()

      PackageInfo(packageName, packageVersion)
    } else {
      UNKNOWN_PACKAGE
    }

  private fun getPackageInfo(context: PklProject?): PackageInfo =
    getPackageInfo(context?.metadata?.packageUri)

  fun analyzeFile(file: VirtualFile): ScipDocumentBuilder {
    val docBuilder = indexBuilder.addDocument(file)
    val module = file.getModule().get() ?: return docBuilder
    val context = module.containingFile.pklProject

    analyzeNode(module, docBuilder, context)
    return docBuilder
  }

  // Extension function to handle common definition node pattern
  private inline fun PklNode.handleAsDefinition(
    docBuilder: ScipDocumentBuilder,
    context: PklProject?,
    packageInfo: ScipAnalyzer.PackageInfo,
    crossinline additionalCheck: () -> Boolean = { true }
  ) {
    if (additionalCheck()) {
      val symbol = createSymbolForDefinition(this, packageInfo)
      addSymbolDefinition(this, symbol, docBuilder, context)
    }
    children.forEach { analyzeNode(it, docBuilder, context) }
  }

  private fun analyzeNode(node: PklNode, docBuilder: ScipDocumentBuilder, context: PklProject?) {
    val packageInfo = getPackageInfo(context)

    when (node) {
      is PklModule -> {
        val symbol = symbolFormatter.formatSymbol(node, packageInfo.name, packageInfo.version)
        addSymbolDefinition(node, symbol, docBuilder, context)
        node.children.forEach { child -> analyzeNode(child, docBuilder, context) }
      }

      is PklClass -> node.handleAsDefinition(docBuilder, context, packageInfo)
      is PklClassMethod -> node.handleAsDefinition(docBuilder, context, packageInfo)
      is PklClassProperty -> node.handleAsDefinition(docBuilder, context, packageInfo)
      is PklObjectProperty -> node.handleAsDefinition(docBuilder, context, packageInfo)
      is PklTypeAlias -> node.handleAsDefinition(docBuilder, context, packageInfo)
      is PklTypedIdentifier -> node.handleAsDefinition(docBuilder, context, packageInfo) { 
        node.identifier != null 
      }

      is PklAccessExpr -> {
        analyzeReference(node, docBuilder, context)
        node.children.forEach { child -> analyzeNode(child, docBuilder, context) }
      }

      is PklTypeName -> {
        analyzeReference(node, docBuilder, context)
        node.children.forEach { child -> analyzeNode(child, docBuilder, context) }
      }

      else -> {
        node.children.forEach { child -> analyzeNode(child, docBuilder, context) }
      }
    }
  }

  private fun createSymbolInformation(node: PklNode, symbol: String): SymbolInformation {
    val builder =
      SymbolInformation.newBuilder().setSymbol(symbol).setKind(symbolFormatter.getSymbolKind(node))

    if (node is PklDocCommentOwner) {
      node.effectiveDocComment(node.containingFile?.pklProject)?.let { doc ->
        builder.addDocumentation(doc)
      }
    }

    if (node is PklNamedNode) {
      builder.setDisplayName(node.name)
    }

    return builder.build()
  }

  private fun addSymbolDefinition(
    node: PklNode,
    symbol: String,
    docBuilder: ScipDocumentBuilder,
    context: PklProject?,
  ) {
    val span =
      when (node) {
        is IdentifierOwner -> node.identifier?.span ?: return
        is PklModule -> node.header?.moduleClause?.span ?: return
        else -> return
      }

    docBuilder.addOccurrence(
      span,
      symbol,
      SymbolRole.Definition,
      symbolFormatter.getSymbolKind(node),
    )

    val symbolInfo = createSymbolInformation(node, symbol)
    docBuilder.addSymbol(symbol, symbolInfo)
  }

  private fun analyzeReference(
    reference: PklNode,
    docBuilder: ScipDocumentBuilder,
    context: PklProject?,
  ) {
    val (identifier, resolvedDefinition) =
      when (reference) {
        is PklAccessExpr -> Pair(reference.identifier, reference.resolve(context))
        is PklTypeName -> Pair(reference.simpleTypeName.identifier, reference.resolve(context))
        else -> return
      }
    if (identifier == null) return
    try {
      if (resolvedDefinition == null) throw Exception("unable to resolve reference")
      val definitionModule =
        resolvedDefinition.enclosingModule ?: throw Exception("unable to resolve module")

      val symbol =
        createSymbolForDefinition(resolvedDefinition, getProjectInfoForModule(definitionModule))

      val kind =
        when {
          (reference is PklAccessExpr && reference.isPropertyAccess) ->
            SymbolInformation.Kind.Property
          reference is PklQualifiedAccessExpr -> SymbolInformation.Kind.Method
          reference is PklUnqualifiedAccessExpr -> SymbolInformation.Kind.Function
          reference is PklTypeName -> SymbolInformation.Kind.Type
          else -> SymbolInformation.Kind.UnspecifiedKind
        }

      docBuilder.addOccurrence(identifier.span, symbol, SymbolRole.ReadAccess, kind)

      // Use the generated symbol to determine if symbol is a local symbol. If so don't add it to
      // the external symbols in the index
      if (isFromDifferentProject(definitionModule, context)) {
        indexBuilder.addExternalSymbolDefinition(
          symbol,
          createSymbolInformation(resolvedDefinition, symbol),
        )
      }
    } catch (e: Exception) {
      // Log error but continue processing
      project
        .getLogger(this::class)
        .error("Error processing reference `${identifier.text}` ${reference.span}: ${e.message}")
    }
  }

  fun buildIndex(): Index {
    return indexBuilder.build()
  }

  private fun isFromDifferentProject(
    definitionModule: PklModule,
    currentProject: PklProject?,
  ): Boolean {
    val definitionProject = definitionModule.containingFile?.pklProject

    return when {
      // If current project is null, assume everything is external
      currentProject == null -> true

      // If definition project is null, it's likely stdlib/external
      definitionProject == null -> true

      // Compare projects
      else -> definitionProject != currentProject
    }
  }

  private fun getProjectInfoForModule(module: PklModule): PackageInfo {
    val pklProject = module.containingFile.pklProject
    val pklPackage = module.containingFile.`package`

    if (module.uri.toString().startsWith("pkl:")) {
      // For built-in modules
      return PackageInfo("pkl", module.effectivePklVersion.toString())
    }

    return when {
      pklProject != null -> getPackageInfo(pklProject)
      pklPackage != null -> getPackageInfo(pklPackage.packageUri)
      else -> UNKNOWN_PACKAGE
    }
  }

  private fun createSymbolBasedOnScope(
    definition: PklNode,
    packageInfo: PackageInfo,
    nameExtractor: (PklNode) -> String?
  ): String {
    val name = nameExtractor(definition) ?: throw Exception("unable to get name")
    
    return if ((definition as? PklModifierListOwner)?.isLocal == true) {
      symbolFormatter.formatLocalSymbol(name, definition.span)
    } else {
      val parentDescriptors = getParentDescriptors(definition)
      symbolFormatter.formatNestedSymbol(definition, parentDescriptors, packageInfo)
    }
  }

  private fun createSymbolForDefinition(definition: PklNode, packageInfo: PackageInfo): String {
    return when (definition) {
      is PklModule -> {
        symbolFormatter.formatSymbol(definition, packageInfo.name, packageInfo.version)
      }
      is PklClass -> createSymbolBasedOnScope(definition, packageInfo) { (it as PklClass).name }
      is PklClassMethod -> createSymbolBasedOnScope(definition, packageInfo) { (it as PklClassMethod).name }
      is PklClassProperty -> createSymbolBasedOnScope(definition, packageInfo) { node ->
        (node as PklClassProperty).identifier?.text
      }
      is PklObjectProperty -> createSymbolBasedOnScope(definition, packageInfo) { node ->
        (node as PklObjectProperty).identifier?.text
      }
      is PklTypedIdentifier -> {
        val identifier = definition.identifier ?: throw Exception("unable to get identifier")
        symbolFormatter.formatLocalSymbol(identifier.text, definition.span)
      }
      is PklTypeAlias -> {
        val parentDescriptors = getParentDescriptors(definition)
        symbolFormatter.formatNestedSymbol(definition, parentDescriptors, packageInfo)
      }
      else -> throw Exception("unknown definition type")
    }
  }

  private fun getParentDescriptors(node: PklNode): String {
    val parents = mutableListOf<String>()
    var current = node.parent

    while (current != null) {
      when (current) {
        is PklModule -> {
          parents.add(symbolFormatter.getDescriptor(current))
          break
        }
        is PklClass -> {
          parents.add(symbolFormatter.getDescriptor(current))
        }
      }
      current = current.parent
    }

    return parents.reversed().joinToString("")
  }
}
