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

import io.github.treesitter.jtreesitter.Node
import java.nio.file.Files
import java.nio.file.Path
import org.pkl.lsp.*
import org.pkl.lsp.FsFile
import org.pkl.lsp.HttpsFile
import org.pkl.lsp.JarFile
import org.pkl.lsp.LspUtil.firstInstanceOf
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.packages.dto.PackageUri
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.util.CachedValue
import org.pkl.lsp.util.GlobResolver

class PklModuleUriImpl(project: Project, override val parent: PklNode, override val ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklModuleUri {
  override val stringConstant: PklStringConstant by lazy {
    children.firstInstanceOf<PklStringConstant>()!!
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModuleUri(this)
  }

  companion object {

    private val lock = Object()

    fun resolve(
      project: Project,
      targetUri: String,
      moduleUri: String,
      sourceFile: VirtualFile,
      enclosingModule: PklModule?,
      context: PklProject?,
    ): PklModule? {
      // if `targetUri == "..."`, add enough context to make it resolvable on its own
      val effectiveTargetUri =
        when (targetUri) {
          "..." ->
            when {
              moduleUri == "..." -> ".../${sourceFile.name}"
              moduleUri.startsWith(".../") -> {
                val nextPathSegment = moduleUri.drop(4).substringBefore("/")
                if (nextPathSegment.isEmpty()) return null
                ".../$nextPathSegment/.."
              }
              else -> return null
            }
          else -> targetUri
        }

      return resolveVirtual(project, effectiveTargetUri, sourceFile, enclosingModule, context)
    }

    /**
     * @param targetUriString The prefix of [moduleUriString] that this reference refers to
     * @param moduleUriString The whole URI
     */
    fun resolveGlob(
      targetUriString: String,
      moduleUriString: String,
      element: PklModuleUri,
      context: PklProject?,
    ): List<VirtualFile>? =
      element.project.cachedValuesManager.getCachedValue(
        "PklModuleUri.resolveGlob($targetUriString, $moduleUriString, ${element.containingFile.path}, ${context?.projectDir})",
        lock,
      ) {
        val result =
          doResolveGlob(targetUriString, moduleUriString, element, context)
            ?: return@getCachedValue null
        val dependencies = buildList {
          add(element.project.pklFileTracker)
          if (context != null) {
            add(element.project.pklProjectManager.syncTracker)
          }
        }
        CachedValue(result, dependencies)
      }

    private fun resolveVirtual(
      project: Project,
      targetUriStr: String,
      sourceFile: VirtualFile,
      enclosingModule: PklModule?,
      context: PklProject?,
    ): PklModule? {

      val targetUri = parseUriOrNull(targetUriStr) ?: return null

      return when (targetUri.scheme) {
        "pkl",
        "https" -> project.virtualFileManager.get(targetUri)?.getModule()?.get()
        "file" ->
          when {
            // be on the safe side and only follow file: URLs from local files
            sourceFile is FsFile -> {
              findByAbsolutePath(sourceFile, targetUri.path)?.getModule()?.get()
            }
            else -> null
          }
        "package" -> {
          if (targetUri.fragment?.startsWith('/') != true) {
            return null
          }
          val vfile =
            getDependencyRoot(project, targetUriStr, enclosingModule, context)
              ?.resolve(targetUri.fragment)
          vfile?.getModule()?.get()
        }
        "modulepath" -> {
          val path = targetUri.path.trimStart('/')
          val absolutePath =
            project.settingsManager.settings.pklModulepath
              .map { it.resolve(path) }
              .firstOrNull(Files::exists) ?: return null
          return sourceFile.project.virtualFileManager
            .get(absolutePath.toUri(), absolutePath)
            ?.getModule()
            ?.get()
        }
        // targetUri is a relative URI
        null -> {
          when {
            sourceFile is HttpsFile -> sourceFile.resolve(targetUriStr)?.getModule()?.get()
            // dependency notation
            targetUriStr.startsWith("@") -> {
              val root = getDependencyRoot(project, targetUriStr, enclosingModule, context)
              if (root != null) {
                val resolvedTargetUri =
                  targetUriStr.substringAfter('/', "").ifEmpty {
                    return root.getModule().get()
                  }
                root.resolve(resolvedTargetUri)?.getModule()?.get()
              } else null
            }
            sourceFile is FsFile || sourceFile is JarFile ->
              findOnFileSystem(sourceFile, targetUri.path)?.getModule()?.get()
            // TODO: handle other types of relative uris
            else -> null
          }
        }
        // unsupported scheme
        else -> null
      }
    }

    private fun doResolveGlob(
      targetUriString: String,
      moduleUriString: String,
      element: PklModuleUri,
      context: PklProject?,
    ): List<VirtualFile>? {
      val sourceFile = element.containingFile
      val parentDir = sourceFile.parent() ?: return null
      // triple-dot URI's are not supported
      if (targetUriString.startsWith("...")) {
        return null
      }
      val isPartialUri = moduleUriString != targetUriString
      val targetUri = parseUriOrNull(targetUriString) ?: return null
      val project = sourceFile.project

      val effectiveScheme = targetUri.scheme ?: sourceFile.uri.scheme

      return when (effectiveScheme) {
        "file" -> {
          val listChildren = { it: VirtualFile -> it.children ?: emptyList() }
          val targetPath = targetUri.path ?: return null
          return when {
            targetPath.startsWith('/') -> {
              val fileRoot = project.virtualFileManager.getFsFile(Path.of("/")) ?: return null
              GlobResolver.resolveAbsoluteGlob(fileRoot, targetPath, isPartialUri, listChildren)
            }
            targetPath.startsWith('@') -> {
              getDependencyRoot(project, targetPath, element.enclosingModule, context)?.let { root
                ->
                val effectiveTargetString = targetPath.substringAfter('/', "")
                GlobResolver.resolveRelativeGlob(
                  root,
                  effectiveTargetString,
                  isPartialUri,
                  listChildren,
                )
              }
            }
            else ->
              GlobResolver.resolveRelativeGlob(
                parentDir,
                targetUriString,
                isPartialUri,
                listChildren,
              )
          }
        }
        "package" -> {
          if (targetUri.fragment?.startsWith('/') != true) {
            return null
          }
          val packageRoot =
            getDependencyRoot(project, targetUriString, element.enclosingModule, context)
              ?: return null
          val listChildren = { it: VirtualFile -> it.children ?: emptyList() }
          val targetPath = targetUri.fragment ?: return null
          val resolved =
            GlobResolver.resolveAbsoluteGlob(packageRoot, targetPath, isPartialUri, listChildren)
          return resolved
        }
        else -> null
      }
    }

    fun getDependencyRoot(
      project: Project,
      targetUriStr: String,
      enclosingModule: PklModule?,
      context: PklProject?,
    ): VirtualFile? {
      if (targetUriStr.startsWith("package:")) {
        val packageUri = PackageUri.create(targetUriStr) ?: return null
        return packageUri.asPackageDependency(context).getRoot(project)
      }
      val dependencyName = targetUriStr.substringBefore('/').drop(1)
      val dependencies = enclosingModule?.dependencies(context) ?: return null
      val dependency = dependencies[dependencyName] ?: return null
      return dependency.getRoot(project)
    }

    private fun findOnFileSystem(sourceFile: VirtualFile, targetPath: String): VirtualFile? {
      return when {
        targetPath.startsWith(".../") -> findTripleDotPathOnFileSystem(sourceFile, targetPath)
        targetPath.startsWith("/") -> findByAbsolutePath(sourceFile, targetPath)
        else -> sourceFile.parent()?.resolve(targetPath)
      }
    }

    private fun findByAbsolutePath(sourceFile: VirtualFile, targetPath: String): VirtualFile? {
      val path = Path.of(targetPath)
      return sourceFile.project.virtualFileManager.get(path)
    }

    private fun findTripleDotPathOnFileSystem(
      sourceFile: VirtualFile,
      targetPath: String,
    ): VirtualFile? {
      val targetPathAfterTripleDot = targetPath.substring(4)

      var currentDir = sourceFile.parent()?.parent()
      while (currentDir != null) {
        val file = currentDir.resolve(targetPathAfterTripleDot)
        if (file == null || file.uri == sourceFile.uri) {
          currentDir = currentDir.parent()
          continue
        }
        return file
      }
      return null
    }
  }
}
