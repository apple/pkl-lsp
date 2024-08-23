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

import java.nio.file.Path
import org.antlr.v4.runtime.tree.ParseTree
import org.pkl.lsp.*
import org.pkl.lsp.LSPUtil.firstInstanceOf
import org.pkl.lsp.packages.dto.PackageUri

class PklModuleUriImpl(
  project: Project,
  override val parent: PklNode,
  override val ctx: ParseTree,
) : AbstractPklNode(project, parent, ctx), PklModuleUri {
  override val stringConstant: PklStringConstant by lazy {
    children.firstInstanceOf<PklStringConstant>()!!
  }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitModuleUri(this)
  }

  companion object {

    fun resolve(
      project: Project,
      targetUri: String,
      moduleUri: String,
      sourceFile: VirtualFile,
      enclosingModule: PklModule?,
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

      return resolveVirtual(project, effectiveTargetUri, sourceFile, enclosingModule)
    }

    private fun resolveVirtual(
      project: Project,
      targetUriStr: String,
      sourceFile: VirtualFile,
      enclosingModule: PklModule?,
    ): PklModule? {

      val targetUri = parseUriOrNull(targetUriStr) ?: return null

      return when (targetUri.scheme) {
        "pkl" -> project.stdlib.getModule(targetUri.schemeSpecificPart)
        "file" ->
          when {
            // be on the safe side and only follow file: URLs from local files
            sourceFile is FsFile -> {
              findByAbsolutePath(sourceFile, targetUri.path)
            }
            else -> null
          }
        "https" ->
          when {
            targetUri.host != null -> project.fileCacheManager.findHttpModule(targetUri)
            else -> null
          }
        "package" -> {
          if (targetUri.fragment?.startsWith('/') != true) {
            return null
          }
          val vfile =
            getDependencyRoot(project, targetUriStr, enclosingModule)?.resolve(targetUri.fragment)
          vfile?.toModule()
        }
        // targetUri is a relative URI
        null -> {
          when {
            sourceFile is HttpsFile -> sourceFile.resolve(targetUriStr).toModule()
            // dependency notation
            targetUriStr.startsWith("@") -> {
              val root = getDependencyRoot(project, targetUriStr, enclosingModule)
              if (root != null) {
                val resolvedTargetUri =
                  targetUriStr.substringAfter('/', "").ifEmpty {
                    return root.toModule()
                  }
                root.resolve(resolvedTargetUri)?.toModule()
              } else null
            }
            sourceFile is FsFile || sourceFile is JarFile ->
              findOnFileSystem(sourceFile, targetUri.path)
            // TODO: handle other types of relative uris
            else -> null
          }
        }
        // unsupported scheme
        else -> null
      }
    }

    private fun getDependencyRoot(
      project: Project,
      targetUriStr: String,
      enclosingModule: PklModule?,
    ): VirtualFile? {
      if (targetUriStr.startsWith("package:")) {
        val packageUri = PackageUri.create(targetUriStr) ?: return null
        return packageUri.asPackageDependency().getRoot(project)
      }
      val dependencyName = targetUriStr.substringBefore('/').drop(1)
      val dependencies = enclosingModule?.dependencies() ?: return null
      val dependency = dependencies[dependencyName] ?: return null
      return dependency.getRoot(project)
    }

    private fun findOnFileSystem(sourceFile: VirtualFile, targetPath: String): PklModule? {
      return when {
        targetPath.startsWith(".../") -> findTripleDotPathOnFileSystem(sourceFile, targetPath)
        targetPath.startsWith("/") -> findByAbsolutePath(sourceFile, targetPath)
        else -> sourceFile.parent()?.resolve(targetPath)?.toModule()
      }
    }

    private fun findByAbsolutePath(sourceFile: VirtualFile, targetPath: String): PklModule? {
      val path = Path.of(targetPath)
      return Builder.pathToModule(path, FsFile(path, sourceFile.project))
    }

    private fun findTripleDotPathOnFileSystem(
      sourceFile: VirtualFile,
      targetPath: String,
    ): PklModule? {
      val targetPathAfterTripleDot = targetPath.substring(4)

      var currentDir = sourceFile.parent()?.parent()
      while (currentDir != null) {
        val file = currentDir.resolve(targetPathAfterTripleDot)
        if (file == null || file.uri == sourceFile.uri) {
          currentDir = currentDir.parent()
          continue
        }
        return file.toModule()
      }
      return null
    }
  }
}
