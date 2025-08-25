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
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.ast.*
import org.pkl.lsp.documentation.toMarkdown
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.scip.ScipSymbolFormatter.formatSymbol
import scip.Scip.*

class ScipAnalyzer(project: Project, private val rootPath: Path) : Component(project) {
  
  private val indexBuilder = ScipIndexBuilder(rootPath)
  private val UNKNOWN_PROJECT = PackageInfo("unknown", "0.0.0")

  data class PackageInfo(val name: String, val version: String)
  
  private fun getPackageInfo(context: PklProject?): PackageInfo {
    // Try to get package info from PKL project
    return if (context != null) {
      val packageName = context.metadata.packageUri.toString().substringBeforeLast("@")
      val packageVersion = context.metadata.packageUri.toString().substringAfterLast("@")
        
      PackageInfo(packageName, packageVersion)
    } else {
      // Use root directory name as package name
      val packageName = rootPath.fileName?.toString() ?: "local"
      PackageInfo(packageName, "0.0.0")
    }
  }
  
  fun analyzeFile(file: VirtualFile): ScipDocumentBuilder {
    val docBuilder = indexBuilder.addDocument(file)
    val module = file.getModule().get() ?: return docBuilder
    val context = file.pklProject
    
    analyzeNode(module, docBuilder, context, "")
    return docBuilder
  }
  
  private fun analyzeNode(
    node: PklNode, 
    docBuilder: ScipDocumentBuilder, 
    context: PklProject?,
    parentDescriptors: String
  ) {
    val packageInfo = getPackageInfo(context)
    
    when (node) {
      is PklModule -> {
        val symbol = ScipSymbolFormatter.formatSymbol(node, packageInfo.name, packageInfo.version)
        addSymbolDefinition(node, symbol, docBuilder, context)
        
        val moduleDescriptor = ScipSymbolFormatter.getDescriptor(node) 
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, moduleDescriptor)
        }
      }
      
      is PklClass -> {
        val symbol = ScipSymbolFormatter.formatNestedSymbol(node, parentDescriptors, packageInfo.name, packageInfo.version)
        addSymbolDefinition(node, symbol, docBuilder, context)
        
        val classDescriptor = parentDescriptors + ScipSymbolFormatter.getDescriptor(node)
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, classDescriptor)
        }
      }
      
      is PklClassMethod -> {
        val symbol = ScipSymbolFormatter.formatNestedSymbol(node, parentDescriptors, packageInfo.name, packageInfo.version)
        addSymbolDefinition(node, symbol, docBuilder, context)
        
        // Methods don't add to the descriptor chain for their children
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      is PklClassProperty -> {
        val symbol = if (node.isLocal) {
          // Local properties are local variables
          val identifier = node.identifier ?: return
          ScipSymbolFormatter.formatLocalSymbol(identifier)
        } else {
          // Regular class properties
          ScipSymbolFormatter.formatNestedSymbol(node, parentDescriptors, packageInfo.name, packageInfo.version)
        }
        addSymbolDefinition(node, symbol, docBuilder, context)
        
        // Properties don't add to the descriptor chain for their children
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      is PklObjectProperty -> {
        val symbol = if (node.isLocal) {
          // Local object properties are local variables
          val identifier = node.identifier ?: return
          ScipSymbolFormatter.formatLocalSymbol(identifier)
        } else {
          // Regular object properties
          ScipSymbolFormatter.formatNestedSymbol(node, parentDescriptors, packageInfo.name, packageInfo.version)
        }
        addSymbolDefinition(node, symbol, docBuilder, context)
        
        // Properties don't add to the descriptor chain for their children
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      is PklTypeAlias -> {
        val symbol = ScipSymbolFormatter.formatNestedSymbol(node, parentDescriptors, packageInfo.name, packageInfo.version)
        addSymbolDefinition(node, symbol, docBuilder, context)
        
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      is PklTypedIdentifier -> {
        // Handle method/function parameters
        val parameterName = node.identifier ?: return
        val symbol = ScipSymbolFormatter.formatLocalSymbol(parameterName)
        addSymbolDefinition(node, symbol, docBuilder, context)
        
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      is PklUnqualifiedAccessExpr -> {
        analyzeReference(node, docBuilder, context)
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      is PklQualifiedAccessExpr -> {
        analyzeReference(node, docBuilder, context)
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      is PklSuperAccessExpr -> {
        analyzeReference(node, docBuilder, context)
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      is PklTypeName -> {
        analyzeTypeReference(node, docBuilder, context)
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
      
      else -> {
        node.children.forEach { child ->
          analyzeNode(child, docBuilder, context, parentDescriptors)
        }
      }
    }
  }
  
  private fun addSymbolDefinition(
    node: PklNode,
    symbol: String,
    docBuilder: ScipDocumentBuilder,
    context: PklProject?
  ) {
    val identifier = when (node) {
      is IdentifierOwner -> node.identifier
      else -> null
    }
    
    if (identifier != null) {
      docBuilder.addOccurrence(
        identifier.span,
        symbol,
        SymbolRole.Definition,
        ScipSymbolFormatter.getSymbolKind(node)
      )
      
      val symbolInfo = createSymbolInformation(node, symbol, context)
      indexBuilder.addSymbolDefinition(symbol, symbolInfo)
    }
  }
  
  private fun analyzeReference(
    accessExpr: PklAccessExpr,
    docBuilder: ScipDocumentBuilder,
    context: PklProject?
  ) {
    val identifier = accessExpr.identifier ?: return
    val packageInfo = getPackageInfo(context)
    
    try {
      val memberName = accessExpr.memberNameText
      val resolvedDefinition = accessExpr.resolve(context)
      
      val symbol = when {
        // Successfully resolved to a definition
        resolvedDefinition != null -> {
          resolveToScipSymbol(resolvedDefinition, memberName, packageInfo, context)
        }
        // Built-in type methods (like String.length, etc.)
        isBuiltinMethod(memberName) -> {
          val builtinSymbol = ScipSymbolFormatter.formatExternalSymbol(packageInfo, "${getBuiltinType(memberName)}#${memberName}().")
          addBuiltinSymbolIfNeeded(builtinSymbol)
          builtinSymbol
        }
        // Fallback: couldn't resolve, assume local
        else -> {
          return
//          val localSymbol = ScipSymbolFormatter.formatLocalSymbol(memberName)
//          ensureSymbolInformation(localSymbol, null, memberName)
//          localSymbol
        }
      }
      
      docBuilder.addOccurrence(
        identifier.span,
        symbol,
        SymbolRole.ReadAccess,
        SymbolInformation.Kind.UnspecifiedKind
      )
    } catch (e: Exception) {
      // Log error but continue processing
      project.getLogger(this::class).error("Error processing reference ${accessExpr.memberNameText}: ${e.message}")
    }
  }
  
  private fun analyzeTypeReference(
    typeName: PklTypeName,
    docBuilder: ScipDocumentBuilder,
    context: PklProject?
  ) {
    val identifier = typeName.simpleTypeName.identifier ?: return
    val packageInfo = getPackageInfo(context)
    
    try {
      val typeNameText = identifier.text
      
      // Try to resolve the type reference using pkl-lsp
      val resolved = try {
        typeName.resolve(context)
      } catch (e: Exception) {
        null
      }
      
      val symbol = when {
        // Successfully resolved type
        resolved != null -> {
          val definitionModule = resolved.enclosingModule
          if (definitionModule != null && isFromDifferentProject(definitionModule, context)) {
            // External type from different project
            val definitionProjectInfo = getProjectInfoForModule(definitionModule)
//            val externalSymbol = ScipSymbolFormatter.formatExternalSymbol(definitionProjectInfo, "${typeNameText}#")
//            addExternalSymbolInfoForResolved(externalSymbol, resolved, typeNameText, definitionProjectInfo.name)
//            externalSymbol
            createSymbolForDefinition(resolved, definitionProjectInfo)
          } else {
            // Internal type
            createSymbolForDefinition(resolved, packageInfo)
          }
        }
        
        // Built-in types
        isBuiltinType(typeNameText) -> {
          addBuiltinTypeSymbolIfNeeded(typeNameText)
          ScipSymbolFormatter.formatExternalSymbol(packageInfo, "${typeNameText}#")
        }
        
        // Unresolved type reference - assume local
        else -> {
          val unresolvedSymbol = ScipSymbolFormatter.formatExternalSymbol(packageInfo, "${typeNameText}#")
          ensureSymbolInformation(unresolvedSymbol, null, typeNameText)
          unresolvedSymbol
        }
      }
      
      docBuilder.addOccurrence(
        identifier.span,
        symbol,
        SymbolRole.ReadAccess,
        SymbolInformation.Kind.Type
      )
    } catch (e: Exception) {
      // Log error but continue processing
      project.getLogger(this::class).error("Error processing type reference ${identifier.text}: ${e.message}")
    }
  }
  
  // Helper functions for built-in detection
  private fun isBuiltinMethod(name: String): Boolean = name in setOf(
    "toUpperCase", "toLowerCase", "length", "isEmpty", 
    "fold", "map", "filter", "round"
  )
  
  private fun isBuiltinType(name: String): Boolean = name in setOf(
    "String", "Int", "Number", "Boolean", "List", "Map", "Duration", "Any"
  )
  
  private fun getBuiltinType(methodName: String): String = when (methodName) {
    "toUpperCase", "toLowerCase", "length", "isEmpty" -> "String"
    "fold", "map", "filter" -> "List"
    "round" -> "Number"
    else -> "Any"
  }
  
  private fun addBuiltinTypeSymbolIfNeeded(typeName: String) {
    val symbol = ScipSymbolFormatter.formatExternalSymbol("pkl", ".", "stdlib", "1.0.0", "${typeName}#")
    val symbolInfo = SymbolInformation.newBuilder()
      .setSymbol(symbol)
      .setKind(SymbolInformation.Kind.Class)
      .setDisplayName(typeName)
      .build()
    indexBuilder.addSymbolDefinition(symbol, symbolInfo)
  }
  
  
  private fun createSymbolInformation(
    node: PklNode,
    symbol: String,
    context: PklProject?
  ): SymbolInformation {
    val builder = SymbolInformation.newBuilder()
      .setSymbol(symbol)
      .setKind(ScipSymbolFormatter.getSymbolKind(node))
    
    if (node is PklDocCommentOwner) {
      node.effectiveDocComment(context)?.let { doc ->
        builder.addDocumentation(doc)
      }
    }
    
    if (node is PklNamedNode) {
      builder.setDisplayName(node.name)
    }
    
    return builder.build()
  }
  
  fun buildIndex(): Index {
    return indexBuilder.build()
  }
  
  private fun addBuiltinSymbolIfNeeded(fullSymbol: String) {
    // Add builtin symbols as symbol definitions (they'll go to external_symbols in final index)
    val symbolInfo = SymbolInformation.newBuilder()
      .setSymbol(fullSymbol)
      .setKind(SymbolInformation.Kind.Method)
      .build()
    indexBuilder.addSymbolDefinition(fullSymbol, symbolInfo)
  }
  
  private fun addExternalSymbolInfoForResolved(
    symbol: String,
    resolvedDefinition: PklNode,
    memberName: String,
    projectName: String
  ) {
    // Create comprehensive SymbolInformation for resolved external symbols
    val builder = SymbolInformation.newBuilder()
      .setSymbol(symbol)
      .setKind(ScipSymbolFormatter.getSymbolKind(resolvedDefinition))
      .setDisplayName(memberName)
    
    // Add documentation if available
    if (resolvedDefinition is PklDocCommentOwner) {
      resolvedDefinition.effectiveDocComment(null)?.let { doc ->
        builder.addDocumentation(doc)
      }
    }
    
    // Add relationship information for external symbols
    when {
      projectName == "stdlib" -> {
        builder.addDocumentation("Pkl standard library symbol")
      }
      projectName == "unknown" -> {
        builder.addDocumentation("External symbol")
      }
      else -> {
        builder.addDocumentation("Symbol from $projectName package")
      }
    }
    
    indexBuilder.addSymbolDefinition(symbol, builder.build())
  }
  
  /**
   * Universal helper to ensure every symbol has corresponding SymbolInformation.
   * This is the core fix - every occurrence should have a SymbolInformation entry.
   */
  private fun ensureSymbolInformation(symbol: String, definition: PklNode?, displayName: String) {
    // Check if we already have SymbolInformation for this symbol (deduplication)
    if (indexBuilder.hasSymbolDefinition(symbol)) {
      return
    }
    
    val builder = SymbolInformation.newBuilder()
      .setSymbol(symbol)
      .setDisplayName(displayName)
    
    // Set the appropriate kind based on the definition node
    if (definition != null) {
      builder.setKind(ScipSymbolFormatter.getSymbolKind(definition))
      
      // Add documentation if available
      if (definition is PklDocCommentOwner) {
        definition.effectiveDocComment(null)?.let { doc ->
          builder.addDocumentation(doc)
        }
      }
    } else {
      // No definition available - use a reasonable default
      builder.setKind(if (symbol.startsWith("local ")) {
        SymbolInformation.Kind.Variable
      } else {
        SymbolInformation.Kind.UnspecifiedKind
      })
    }
    
    indexBuilder.addSymbolDefinition(symbol, builder.build())
  }

  private fun resolveToScipSymbol(
    definition: PklNode,
    memberName: String,
    packageInfo: PackageInfo,
    context: PklProject?
  ): String {
    // Use pkl-lsp's project system to determine if the symbol is external
    val definitionModule = definition.enclosingModule
    
    return when {
      // If we can't determine the module, assume external
      definitionModule == null -> {
        val externalSymbol = ScipSymbolFormatter.formatExternalSymbol(definition, UNKNOWN_PROJECT)
        addExternalSymbolInfoForResolved(externalSymbol, definition, memberName, "unknown")
        externalSymbol
      }
      
      // Check if the definition is in a different project
      isFromDifferentProject(definitionModule, context) -> {
        // External symbol from different project
        val definitionProjectInfo = getProjectInfoForModule(definitionModule)
        createSymbolForDefinition(definition, definitionProjectInfo)
      }
      
      // Internal symbol from same project
      else -> {
        createSymbolForDefinition(definition, packageInfo)
      }
    }
  }
  
  private fun isFromDifferentProject(definitionModule: PklModule, currentProject: PklProject?): Boolean {
    val definitionProject = definitionModule.containingFile?.pklProject
    
    return when {
      // If current project is null, assume everything is external
      currentProject == null -> true
      
      // If definition project is null, it's likely stdlib/external
      definitionProject == null -> true
      
      // Compare project URIs
      else -> definitionProject.metadata.projectFileUri != currentProject.metadata.projectFileUri
    }
  }
  
  private fun getProjectInfoForModule(module: PklModule): PackageInfo {
    val pklProject = module.containingFile.pklProject

    if (module.uri.toString().startsWith("pkl:")) {
      // For stdlib modules
      return PackageInfo(module.uri.toString(), "0.0.0")
    }
    
    return if (pklProject != null) {
      getPackageInfo(pklProject)
    } else {
      // Fallback for other external modules
      UNKNOWN_PROJECT
    }
  }
  
  private fun createSymbolForDefinition(definition: PklNode, packageInfo: PackageInfo): String {
    // Create the appropriate SCIP symbol based on the definition type
    val symbol = when (definition) {
      is PklModule -> {
        ScipSymbolFormatter.formatSymbol(definition, packageInfo.name, packageInfo.version)
      }
      is PklClass -> {
        val parentDescriptors = getParentDescriptors(definition)
        ScipSymbolFormatter.formatNestedSymbol(definition, parentDescriptors, packageInfo.name, packageInfo.version)
      }
      is PklClassMethod -> {
        val parentDescriptors = getParentDescriptors(definition)
        ScipSymbolFormatter.formatNestedSymbol(definition, parentDescriptors, packageInfo.name, packageInfo.version)
      }
      is PklClassProperty -> {
        val parentDescriptors = getParentDescriptors(definition)
        ScipSymbolFormatter.formatNestedSymbol(definition, parentDescriptors, packageInfo.name, packageInfo.version)
      }
      is PklObjectProperty -> {
        val parentDescriptors = getParentDescriptors(definition)
        ScipSymbolFormatter.formatNestedSymbol(definition, parentDescriptors, packageInfo.name, packageInfo.version)
      }
      is PklTypedIdentifier -> {
        // Parameter or local variable
        val identifier = definition.identifier ?: return ""
        ScipSymbolFormatter.formatLocalSymbol(identifier)
      }
      else -> {
        return ""
      }
    }

    ensureSymbolInformation(symbol, definition, getNodeName(definition))
    
    return symbol
  }
  
  private fun getParentDescriptors(node: PklNode): String {
    val parents = mutableListOf<String>()
    var current = node.parent
    
    while (current != null) {
      when (current) {
        is PklModule -> {
          parents.add(ScipSymbolFormatter.getDescriptor(current))
          break
        }
        is PklClass -> {
          parents.add(ScipSymbolFormatter.getDescriptor(current))
        }
      }
      current = current.parent
    }
    
    return parents.reversed().joinToString("")
  }
  
  private fun getNodeName(node: PklNode): String {
    return when (node) {
      is IdentifierOwner -> node.identifier?.text ?: "unknown"
      else -> "unknown"
    }
  }
}