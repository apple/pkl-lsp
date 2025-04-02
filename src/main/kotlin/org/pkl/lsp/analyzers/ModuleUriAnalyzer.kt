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
package org.pkl.lsp.analyzers

import java.net.URI
import java.net.URISyntaxException
import org.pkl.lsp.*
import org.pkl.lsp.actions.PklDownloadPackageAction
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.PackageDependency
import org.pkl.lsp.packages.dto.Checksums
import org.pkl.lsp.packages.dto.PackageUri
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.packages.dto.Version

class ModuleUriAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, diagnosticsHolder: DiagnosticsHolder): Boolean {
    if (node !is PklModuleUriOwner) {
      return true
    }
    val moduleUri = node.moduleUri ?: return true
    val uriStr = moduleUri.stringConstant.escapedText() ?: return false
    val context = node.containingFile.pklProject
    val isGlob = (node as? PklImportBase)?.isGlob ?: false
    if (isGlob) {
      analyzeGlobUri(moduleUri, uriStr, diagnosticsHolder, context)
      return true
    }
    val resolved = moduleUri.resolve(context)
    if (
      checkDependencyNotation(
        moduleUri,
        resolved?.let { listOf(it) } ?: emptyList(),
        diagnosticsHolder,
        context,
      )
    ) {
      return false
    }

    if (resolved != null) {
      return false
    }

    if (uriStr.startsWith("package:") && analyzePackageUri(moduleUri, uriStr, diagnosticsHolder)) {
      return false
    }
    diagnosticsHolder.addWarning(
      moduleUri.stringConstant,
      ErrorMessages.create("cannotResolveImport"),
    )
    return true
  }

  private fun analyzeGlobUri(
    element: PklModuleUri,
    uriText: String,
    holder: DiagnosticsHolder,
    context: PklProject?,
  ) {
    val scheme = parseUriOrNull(uriText)?.scheme ?: element.containingFile.uri.scheme
    if (checkScheme(element, scheme, holder)) {
      return
    }
    if (uriText.startsWith("...")) {
      holder.addWarning(element.stringConstant, ErrorMessages.create("cannotGlobTripleDots"))
      return
    }
    val resolved = element.resolveGlob(context)
    if (resolved.isEmpty()) {
      holder.addWarning(element.stringConstant, ErrorMessages.create("globPatternHasNoMatches"))
    }
  }

  private fun checkScheme(
    element: PklModuleUri,
    scheme: String,
    holder: DiagnosticsHolder,
  ): Boolean {
    // only warn on known unglobbable schemes
    if (scheme == "pkl" || scheme == "http" || scheme == "https") {
      holder.addWarning(element.stringConstant, "Scheme $scheme is not globbable")
      return true
    }
    return false
  }

  private fun analyzePackageUri(
    moduleUri: PklModuleUri,
    uriText: String,
    holder: DiagnosticsHolder,
  ): Boolean {
    val uri =
      try {
        URI(uriText)
      } catch (_: URISyntaxException) {
        holder.addWarning(moduleUri.stringConstant, ErrorMessages.create("malformedUri", uriText))
        return true
      }
    if (uri.authority == null) {
      holder.addWarning(moduleUri.stringConstant, ErrorMessages.create("missingPackageAuthority"))
      return true
    }
    if (uri.path == null) {
      holder.addWarning(moduleUri.stringConstant, ErrorMessages.create("missingPackagePath"))
      return true
    }
    val versionAndChecksumPart = uri.path.substringAfterLast('@', "")
    if (versionAndChecksumPart.isEmpty()) {
      holder.addWarning(moduleUri.stringConstant, ErrorMessages.create("missingPackageVersion"))
      return true
    }
    val versionAndChecksumParts = versionAndChecksumPart.split("::")
    val versionStr = versionAndChecksumParts.first()
    val version = Version.parseOrNull(versionStr)
    if (version == null) {
      val offset = moduleUri.stringConstant.text.lastIndexOf('@') + 1
      val span = moduleUri.stringConstant.span
      holder.addWarning(
        moduleUri,
        ErrorMessages.create("invalidSemver", versionStr),
        span = span.spliceLine(offset, versionStr.length),
      )
      return true
    }
    val checksum =
      if (versionAndChecksumParts.size == 2) {
        val checksumParts = versionAndChecksumParts[1].split(':')
        if (checksumParts.size != 2) {
          holder.addWarning(
            moduleUri.stringConstant,
            ErrorMessages.create("invalidPackageChecksum", versionAndChecksumPart),
          )
          return true
        }
        val (algo, value) = checksumParts
        if (algo != "sha256") {
          holder.addWarning(
            moduleUri.stringConstant,
            ErrorMessages.create("invalidPackageChecksum", versionAndChecksumPart),
          )
          return true
        }
        Checksums(value)
      } else null
    if (uri.fragment == null) {
      holder.addWarning(moduleUri, ErrorMessages.create("missingPackageFragment"))
      return true
    }
    if (!uri.fragment.startsWith('/')) {
      val offset = moduleUri.stringConstant.text.lastIndexOf('#') + 1
      holder.addWarning(
        moduleUri,
        ErrorMessages.create("invalidFragmentPath"),
        span = moduleUri.stringConstant.span.drop(offset),
      )
      return true
    }
    val packageService = project.packageManager
    val packageUri = PackageUri(uri.authority, uri.path, version, checksum)
    val packageDependency = PackageDependency(packageUri, null, null)
    val roots = packageService.getLibraryRoots(packageDependency)
    if (roots == null) {
      holder.addWarning(
        moduleUri.stringConstant,
        ErrorMessages.create("missingPackageSources", packageUri.toString()),
      ) {
        actions = listOf(PklDownloadPackageAction(project, packageUri))
      }
      return true
    }
    return false
  }

  private fun checkDependencyNotation(
    element: PklModuleUri,
    resolved: List<PklNode>,
    holder: DiagnosticsHolder,
    context: PklProject?,
  ): Boolean {
    if (element.stringConstant.escapedText()?.startsWith("@") == false) return false
    if (resolved.isNotEmpty() && resolved.all { it.isInPackage }) return false

    val dependencyName =
      element.stringConstant.escapedText()?.substringBefore('/')?.drop(1) ?: return false
    if (resolved.isNotEmpty()) return true
    val deps = element.enclosingModule?.dependencies(context) ?: return false
    if (deps.containsKey(dependencyName)) {
      holder.addWarning(
        element.stringConstant,
        ErrorMessages.create("missingPackageSources", dependencyName),
      ) {
        actions = listOf(PklDownloadPackageAction(project, deps[dependencyName]!!.packageUri))
      }
      return true
    } else {
      holder.addWarning(
        element.stringConstant,
        ErrorMessages.create("cannotFindDependency", dependencyName),
      )
      return true
    }
  }
}
