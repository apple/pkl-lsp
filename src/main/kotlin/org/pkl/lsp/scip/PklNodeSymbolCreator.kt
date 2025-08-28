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

import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.packages.dto.PackageUri
import scip.Scip.*

/**
 * Handles symbol creation for different Pkl AST node types.
 * This class encapsulates the logic for creating SCIP symbols based on Pkl-specific nodes,
 * making it easier to maintain and extend as new node types are added.
 */
class PklNodeSymbolCreator(
  private val symbolFormatter: ScipSymbolFormatter,
  private val getParentDescriptors: (PklNode) -> String
) {
  
  data class PackageInfo(val name: String, val version: String)
  
  private val UNKNOWN_PACKAGE = PackageInfo(".", ".")
  
  /**
   * Creates a SCIP symbol for the given Pkl node definition.
   */
  fun createSymbolForDefinition(definition: PklNode, packageInfo: PackageInfo): String {
    return when (definition) {
      is PklModule -> createModuleSymbol(definition, packageInfo)
      is PklClass -> createClassSymbol(definition, packageInfo)
      is PklClassMethod -> createClassMethodSymbol(definition, packageInfo)
      is PklClassProperty -> createClassPropertySymbol(definition, packageInfo)
      is PklObjectProperty -> createObjectPropertySymbol(definition, packageInfo)
      is PklTypedIdentifier -> createTypedIdentifierSymbol(definition)
      is PklTypeAlias -> createTypeAliasSymbol(definition, packageInfo)
      else -> throw ScipAnalysisException.UnknownDefinitionTypeException(
        nodeType = definition::class.simpleName ?: "Unknown"
      )
    }
  }
  
  /**
   * Creates symbol for module nodes (top-level files).
   */
  private fun createModuleSymbol(module: PklModule, packageInfo: PackageInfo): String {
    return symbolFormatter.formatSymbol(module, packageInfo.name, packageInfo.version)
  }
  
  /**
   * Creates symbol for class definitions, handling local vs nested scope.
   */
  private fun createClassSymbol(clazz: PklClass, packageInfo: PackageInfo): String {
    return if ((clazz as? PklModifierListOwner)?.isLocal == true) {
      symbolFormatter.formatLocalSymbol(clazz.name, clazz.span)
    } else {
      val parentDescriptors = getParentDescriptors(clazz)
      symbolFormatter.formatNestedSymbol(clazz, parentDescriptors, packageInfo)
    }
  }
  
  /**
   * Creates symbol for class method definitions, handling local vs nested scope.
   */
  private fun createClassMethodSymbol(method: PklClassMethod, packageInfo: PackageInfo): String {
    return if ((method as? PklModifierListOwner)?.isLocal == true) {
      symbolFormatter.formatLocalSymbol(method.name, method.span)
    } else {
      val parentDescriptors = getParentDescriptors(method)
      symbolFormatter.formatNestedSymbol(method, parentDescriptors, packageInfo)
    }
  }
  
  /**
   * Creates symbol for class property definitions, handling identifier extraction and scope.
   */
  private fun createClassPropertySymbol(property: PklClassProperty, packageInfo: PackageInfo): String {
    return if ((property as? PklModifierListOwner)?.isLocal == true) {
      val identifier = property.identifier ?: throw ScipAnalysisException.IdentifierExtractionException(
        nodeType = "PklClassProperty",
        location = property.span
      )
      symbolFormatter.formatLocalSymbol(identifier.text, property.span)
    } else {
      val parentDescriptors = getParentDescriptors(property)
      symbolFormatter.formatNestedSymbol(property, parentDescriptors, packageInfo)
    }
  }
  
  /**
   * Creates symbol for object property definitions, handling identifier extraction and scope.
   */
  private fun createObjectPropertySymbol(property: PklObjectProperty, packageInfo: PackageInfo): String {
    return if ((property as? PklModifierListOwner)?.isLocal == true) {
      val identifier = property.identifier ?: throw ScipAnalysisException.IdentifierExtractionException(
        nodeType = "PklObjectProperty",
        location = property.span
      )
      symbolFormatter.formatLocalSymbol(identifier.text, property.span)
    } else {
      val parentDescriptors = getParentDescriptors(property)
      symbolFormatter.formatNestedSymbol(property, parentDescriptors, packageInfo)
    }
  }
  
  /**
   * Creates symbol for typed identifier nodes (parameters, local variables).
   * These are always treated as local symbols.
   */
  private fun createTypedIdentifierSymbol(typedId: PklTypedIdentifier): String {
    val identifier = typedId.identifier ?: throw ScipAnalysisException.IdentifierExtractionException(
      nodeType = "PklTypedIdentifier",
      location = typedId.span
    )
    return symbolFormatter.formatLocalSymbol(identifier.text, typedId.span)
  }
  
  /**
   * Creates symbol for type alias definitions.
   * These are always treated as nested (non-local) symbols.
   */
  private fun createTypeAliasSymbol(typeAlias: PklTypeAlias, packageInfo: PackageInfo): String {
    val parentDescriptors = getParentDescriptors(typeAlias)
    return symbolFormatter.formatNestedSymbol(typeAlias, parentDescriptors, packageInfo)
  }
  
  /**
   * Extracts package information from various Pkl project contexts.
   */
  fun getPackageInfoForProject(pklProject: PklProject?): PackageInfo =
    if (pklProject != null) {
      val packageName = pklProject.metadata?.packageUri?.toString()?.substringBeforeLast("@") ?: "."
      val packageVersion = pklProject.metadata?.packageUri?.version?.toString() ?: "."
      PackageInfo(packageName, packageVersion)
    } else {
      UNKNOWN_PACKAGE
    }
  
  /**
   * Extracts package information from a PackageUri.
   */
  fun getPackageInfo(packageUri: PackageUri): PackageInfo {
    val packageName = packageUri.toString().substringBeforeLast("@")
    val packageVersion = packageUri.version.toString()
    return PackageInfo(packageName, packageVersion)
  }
  
  /**
   * Extracts package information for a specific module, handling built-in modules.
   */
  fun getPackageInfoForModule(module: PklModule): PackageInfo {
    val pklProject = module.containingFile.pklProject
    val pklPackage = module.containingFile.`package`

    if (module.uri.toString().startsWith("pkl:")) {
      // For built-in modules
      return PackageInfo("pkl", module.effectivePklVersion.toString())
    }

    return when {
      pklProject != null -> getPackageInfoForProject(pklProject)
      pklPackage != null -> {
        val packageName = pklPackage.packageUri.toString().substringBeforeLast("@")
        val packageVersion = pklPackage.packageUri.version.toString()
        PackageInfo(packageName, packageVersion)
      }
      else -> UNKNOWN_PACKAGE
    }
  }
}