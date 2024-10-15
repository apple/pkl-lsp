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

import com.google.gson.JsonSyntaxException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.pkl.lsp.*
import org.pkl.lsp.LspUtil.toRange
import org.pkl.lsp.ast.*
import org.pkl.lsp.documentation.toMarkdown
import org.pkl.lsp.packages.dto.PackageAssetUri

class ModuleUriCompletionProvider(project: Project, private val packageUriOnly: Boolean) :
  Component(project), CompletionProvider {
  companion object {
    private const val PKL_SCHEME = "pkl:"
    private const val FILE_SCHEME = "file:///"
    private const val HTTPS_SCHEME = "https://"
    private const val PACKAGE_SCHEME = "package://"

    private val SCHEME_ELEMENTS =
      listOf(
        CompletionItem(PKL_SCHEME),
        CompletionItem(FILE_SCHEME),
        CompletionItem(HTTPS_SCHEME),
        CompletionItem(PACKAGE_SCHEME),
      )

    private val GLOBBABLE_SCHEME_ELEMENTS =
      listOf(CompletionItem(FILE_SCHEME), CompletionItem(PACKAGE_SCHEME))

    data class ModuleUriCompletionData(val moduleUri: String, val importNodeLocation: Location)
  }

  override fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
  ) {
    val import =
      node.parentOfTypes(PklImportBase::class, /* stop class */ PklObjectBody::class)
        as? PklImportBase ?: return
    val isGlobImport = import.isGlob
    val stringChars = import.moduleUri?.stringConstant ?: return
    val targetUri =
      stringChars.escapedText()?.substring(0, params.position.character - stringChars.span.beginCol)
        ?: return
    val pklModule = stringChars.enclosingModule!!
    val project = stringChars.project
    return complete(targetUri, stringChars, isGlobImport, pklModule, project, collector)
  }

  override fun resolveCompletionItem(
    unresolved: CompletionItem
  ): CompletableFuture<CompletionItem>? {
    val data = unresolved.data as? com.google.gson.JsonElement ?: return null
    val parsed =
      try {
        gson.fromJson(data, ModuleUriCompletionData::class.java)
      } catch (e: JsonSyntaxException) {
        return null
      }
    val virtualFileManager = project.virtualFileManager
    val originalModuleFuture =
      virtualFileManager.get(URI(parsed.importNodeLocation.uri))?.getModule() ?: return null
    val targetModuleFuture =
      virtualFileManager.get(URI(parsed.moduleUri))?.getModule() ?: return null
    return listOf(originalModuleFuture, targetModuleFuture).sequence().thenApply {
      (originalModule, targetModule) ->
      if (originalModule == null || targetModule == null) return@thenApply unresolved
      val line = parsed.importNodeLocation.range.start.line + 1
      val column = parsed.importNodeLocation.range.start.character + 1
      val originalNode = originalModule.findBySpan(line, column, false)
      val docs = targetModule.toMarkdown(originalNode, originalNode?.containingFile?.pklProject)
      unresolved.documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, docs))
      unresolved
    }
  }

  private fun completionItemData(node: PklNode, moduleUri: String): ModuleUriCompletionData {
    return ModuleUriCompletionData(
      moduleUri = moduleUri,
      importNodeLocation =
        Location().apply {
          this.uri = node.enclosingModule!!.uri.toString()
          this.range = node.beginningSpan().toRange()
        },
    )
  }

  // assumes caret location is immediately behind [moduleUri]
  private fun complete(
    targetUri: String,
    stringChars: PklStringConstant,
    isGlobImport: Boolean,
    sourceModule: PklModule,
    project: Project,
    collector: MutableList<CompletionItem>,
  ) {
    when {
      targetUri.startsWith(PKL_SCHEME) && !isGlobImport && !packageUriOnly -> {
        for (module in sourceModule.project.stdlib.files.values) {
          val completion = module.uri.toString()
          collector.add(
            CompletionItem().apply {
              kind = CompletionItemKind.Class
              label = completion
              data = completionItemData(stringChars, completion)
              textEdit =
                Either.forLeft(
                  TextEdit().apply {
                    this.newText = completion
                    this.range = stringChars.contentsSpan().toRange()
                  }
                )
            }
          )
        }
      }
      targetUri.startsWith(FILE_SCHEME) && !packageUriOnly -> {
        val roots = listOf(project.virtualFileManager.get(Path.of("/"))!!)
        completeHierarchicalUri(
          roots,
          ".",
          targetUri.substring(8),
          collector,
          isGlobImport = isGlobImport,
          isAbsoluteUri = true,
          stringCharsNode = stringChars,
        )
      }
      targetUri.startsWith(PACKAGE_SCHEME) -> {
        // if there is no fragment part, offer completions from the cached directory
        when (val packageAssetUri = PackageAssetUri.create(targetUri)) {
          null -> {
            if (targetUri.endsWith("@")) {
              completePackageBaseUriVersions(targetUri, collector)
            } else {
              completePackageBaseUris(collector)
            }
          }
          else -> {
            val packageService = project.packageManager
            val libraryRoots =
              packageService.getLibraryRoots(packageAssetUri.packageUri.asPackageDependency(null))
                ?: return
            completeHierarchicalUri(
              listOf(libraryRoots.packageRoot),
              ".",
              packageAssetUri.assetPath,
              collector,
              isGlobImport = isGlobImport,
              isAbsoluteUri = true,
              stringCharsNode = stringChars,
            )
          }
        }
      }
      targetUri.startsWith("@") && !packageUriOnly -> {
        val dependencies = sourceModule.dependencies(null) ?: return
        if (!targetUri.contains('/')) {
          collector.addAll(
            dependencies.keys.map { name ->
              CompletionItem().apply {
                kind = CompletionItemKind.Module
                label = "@$name"
                insertTextFormat = InsertTextFormat.PlainText
              }
            }
          )
        } else {
          PklModuleUriImpl.getDependencyRoot(
              project,
              targetUri,
              sourceModule,
              sourceModule.containingFile.pklProject,
            )
            ?.let { root ->
              val delimiter = targetUri.indexOf('/')
              val resolvedTargetUri = if (delimiter == -1) "/" else targetUri.drop(delimiter)
              completeHierarchicalUri(
                listOf(root),
                ".",
                resolvedTargetUri,
                collector,
                isGlobImport = isGlobImport,
                isAbsoluteUri = false,
                stringCharsNode = stringChars,
              )
            }
        }
      }
      !targetUri.contains(':') && packageUriOnly -> {
        collector.add(
          CompletionItem().apply {
            label = PACKAGE_SCHEME
            insertTextFormat = InsertTextFormat.PlainText
          }
        )
      }
      !targetUri.contains(':') -> {
        if (!targetUri.contains('/')) {
          collector.addAll(if (isGlobImport) GLOBBABLE_SCHEME_ELEMENTS else SCHEME_ELEMENTS)
        }
        completeRelativeUri(targetUri, sourceModule, isGlobImport, collector, stringChars)
      }
    }
  }

  private fun collectPackages(basePath: Path, nameParts: List<String> = emptyList()): Set<String> {
    return if (Files.isDirectory(basePath))
      Files.list(basePath).toList().flatMapTo(mutableSetOf()) { file ->
        if (file.name.contains("@")) {
          listOf(nameParts.joinToString("/") { it } + "/" + file.name.substringBeforeLast("@"))
        } else {
          collectPackages(file, nameParts + file.name)
        }
      }
    else emptySet()
  }

  private fun completePackageBaseUriVersions(
    targetUri: String,
    collector: MutableList<CompletionItem>,
  ) {
    val path = targetUri.drop(10)
    val basePath = path.substringBeforeLast('/')
    val packageName = path.substringAfterLast('/')
    val packages = buildSet {
      packages1CacheDir.resolve(basePath)?.listDirectoryEntries()?.map { it.name }?.let(::addAll)
      packages2CacheDir
        .resolve(basePath)
        ?.listDirectoryEntries()
        ?.map { decodePath(it.name) }
        ?.let(::addAll)
    }
    for (pkg in packages) {
      if (pkg.startsWith(packageName)) {
        val item =
          CompletionItem().apply {
            val version = pkg.substringAfterLast('@')
            label = version
            insertText = version.let { if (!packageUriOnly) "$it#/" else it }
            insertTextFormat = InsertTextFormat.PlainText
          }
        collector.add(item)
      }
    }
  }

  private fun completePackageBaseUris(collector: MutableList<CompletionItem>) {
    val packages = buildSet {
      addAll(collectPackages(packages1CacheDir.file))
      addAll(collectPackages(packages2CacheDir.file).map(::decodePath))
    }
    if (packages.isEmpty()) return
    for (pkg in packages) {
      val completionText = "$pkg@"
      val pkgName = pkg.substringAfterLast('/')
      val completion =
        CompletionItem().apply {
          label = pkgName
          documentation = Either.forLeft(pkg)
          insertText = completionText
          insertTextFormat = InsertTextFormat.PlainText
        }
      collector.add(completion)
    }
  }

  private fun completeHierarchicalUri(
    roots: List<VirtualFile>,
    relativeSourceDirPath: String,
    // the path to complete
    relativeTargetFilePath: String,
    collector: MutableList<CompletionItem>,
    isGlobImport: Boolean,
    isAbsoluteUri: Boolean,
    stringCharsNode: PklNode,
  ) {
    val isTripleDotDirPath = relativeTargetFilePath.startsWith("...")

    for (root in roots) {
      val dirs =
        if (isTripleDotDirPath) {
          resolveTripleDotDirPath(root, relativeSourceDirPath, relativeTargetFilePath)
        } else {
          val sourceDir = root.resolve(relativeSourceDirPath) ?: return
          val targetPathDir =
            if (relativeTargetFilePath.endsWith("/")) relativeTargetFilePath
            else relativeTargetFilePath.substringBeforeLast('/')
          val resolved = sourceDir.resolve(targetPathDir) ?: return
          listOf(resolved)
        }

      for (dir in dirs) {
        if (!dir.isDirectory) continue
        dir.children!!.forEach { child ->
          val isDirectory = child.isDirectory
          val extension = child.path.extension
          val name = child.name
          if (
            !name.startsWith('.') &&
              (isDirectory || extension == "pkl" || extension == "pcf" || name == "PklProject")
          ) {
            // prefix with `./` if name starts with `@`, becuase this is reserved for dependency
            // notation.
            val completionName =
              if (name.startsWith("@") && relativeTargetFilePath.count() == 1) "./$name" else name
            var completionText = completionName
            if (isDirectory) {
              completionText = "$completionText/"
            }
            if (isGlobImport) {
              val replacement = "\\\\\\\\$1"
              completionText = completionText.replace(Regex("([*\\\\{\\[])"), replacement)
            }
            if (isAbsoluteUri) {
              completionText = URI(null, null, completionText, null).rawPath
            }
            val completion =
              CompletionItem().apply {
                kind = if (isDirectory) CompletionItemKind.Folder else CompletionItemKind.Module
                label = completionName
                insertText = completionText
                data = completionItemData(stringCharsNode, child.uri.toString())
              }
            collector.add(completion)
          }
        }
      }
    }
  }

  private fun resolveTripleDotDirPath(
    root: VirtualFile,
    // source file's directory path relative to its source or class root
    relativeSourceDirPath: String,
    // target directory path relative to source file's directory path
    relativeTargetDirPath: String,
  ): List<VirtualFile> { // list of directories that [relativeTargetDirPath] may refer to

    if (relativeSourceDirPath == ".") return emptyList()

    require(relativeTargetDirPath == "..." || relativeTargetDirPath.startsWith(".../")) {
      "resolveTripleDotDirPath called with path that doesn't start with triple dots"
    }

    val targetDirPathAfterTripleDot =
      if (relativeTargetDirPath == "...") Path.of("") else relativeTargetDirPath.drop(4)

    return buildList {
      var currentDir: VirtualFile? = root.resolve(relativeSourceDirPath)
      while (currentDir != null) {
        currentDir = currentDir.parent()
        val sourceDir = currentDir ?: root
        val targetDir = sourceDir.resolve(targetDirPathAfterTripleDot.toString())
        if (targetDir != null) add(targetDir)
      }
    }
  }

  private fun completeRelativeUri(
    targetUri: String,
    sourceModule: PklModule,
    isGlobImport: Boolean,
    collector: MutableList<CompletionItem>,
    stringCharsNode: PklNode,
  ) {
    val sourceFile = sourceModule.virtualFile
    val sourceDir = sourceFile.parent() ?: return
    val sourceRoot = sourceModule.virtualFile.path.root
    val isAbsoluteTargetFilePath = targetUri.startsWith('/')
    val relativeTargetFilePath = if (isAbsoluteTargetFilePath) targetUri.drop(1) else targetUri
    if (isGlobImport && targetUri.startsWith("...")) {
      return
    }
    val root = sourceModule.virtualFile.root ?: return
    val relativeSourceDirPath =
      if (isAbsoluteTargetFilePath) {
        "."
      } else {
        sourceRoot.relativize(sourceDir.path).toString()
      }
    completeHierarchicalUri(
      listOf(root),
      relativeSourceDirPath,
      relativeTargetFilePath,
      collector,
      isGlobImport = isGlobImport,
      isAbsoluteUri = false,
      stringCharsNode = stringCharsNode,
    )
  }
}
