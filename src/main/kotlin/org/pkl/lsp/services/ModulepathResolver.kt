/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.services

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.*
import kotlin.io.path.*
import org.pkl.lsp.Component
import org.pkl.lsp.FsFile
import org.pkl.lsp.JarFile
import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.ensureJarFileSystem
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.util.CachedValue

class ModulepathResolver(project: Project) : Component(project) {

  companion object {
    val execHeader =
      "#!/bin/sh\n      exec java  -jar $0 \"$@\"".toByteArray(StandardCharsets.UTF_8)
  }

  private val lock = Any()

  private fun modulepaths(context: PklProject?): List<Path> {
    return project.cachedValuesManager.getCachedValue("modulepaths(${context?.projectDir})", lock) {
      val modulePathFromPklProject =
        context?.metadata?.evaluatorSettings?.modulePath?.map(context.projectDir::resolve).orEmpty()
      val explicitlyConfiguredPath = project.settingsManager.settings.modulepath
      val dependencies = listOf(project.pklProjectManager.syncTracker, project.settingsManager)
      CachedValue(
        (modulePathFromPklProject + explicitlyConfiguredPath).map(::normalizeArchivePath),
        dependencies,
      )
    } ?: emptyList()
  }

  private fun normalizeArchivePath(path: Path): Path {
    val normalizedPath = path.normalize().toAbsolutePath()
    if (isArchive(normalizedPath)) {
      val uri = URI.create("jar:${normalizedPath.toUri()}!/")
      ensureJarFileSystem(uri)
      return Paths.get(uri)
    }
    return normalizedPath
  }

  private fun isArchive(path: Path): Boolean {
    if (!Files.isRegularFile(path)) return false
    if (path.extension == "zip" || path.extension == "jar") return true
    return try {
      Files.newInputStream(path).use { stream ->
        val buffer = stream.readNBytes(execHeader.size)
        // zip magic number or executable jar (e.g. jpkl)
        (buffer.size >= 4 &&
          buffer.copyOfRange(0, 4).contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) ||
          buffer.contentEquals(execHeader)
      }
    } catch (_: Exception) {
      false
    }
  }

  fun resolveAbsolute(path: String, context: PklProject?): VirtualFile? {
    assert(path.startsWith("/")) { "path is not an absolute path" }
    val roots = modulepaths(context)
    return roots.firstNotNullOfOrNull { root ->
      project.virtualFileManager.get(root.resolve(path.drop(1)))
    }
  }

  fun resolve(file: VirtualFile, path: String): VirtualFile? {
    assert(file.isOnModulePath) { "ModulepathResolver.resolve() called by file not on modulepath" }
    val absolutePath =
      when {
        path.startsWith("/") -> path
        else -> {
          val roots = modulepaths(file.pklProject)
          val myRoot = roots.first { file.path.startsWith(it) }
          val myRelativePath = myRoot.relativize(file.path)
          "/${myRelativePath.resolve(path)}"
        }
      }
    return resolveAbsolute(absolutePath, file.pklProject)
  }

  private fun getFile(path: Path): VirtualFile? = project.virtualFileManager.get(path)

  fun paths(context: PklProject?): List<VirtualFile> = modulepaths(context).mapNotNull(::getFile)

  fun listChildren(file: VirtualFile): List<VirtualFile> {
    if (!isOnModulePath(file)) {
      return file.path.listDirectoryEntries().mapNotNull { project.virtualFileManager.get(it) }
    }
    val path = file.path
    val paths = modulepaths(file.pklProject)
    val root = paths.firstOrNull(path::startsWith) ?: return emptyList()
    val relative = runCatching { root.relativize(path) }.getOrNull() ?: return emptyList()
    return paths
      .flatMap { it.resolve(relative).normalize().listDirectoryEntries() }
      .map { it.toAbsolutePath().normalize() }
      .distinct()
      .mapNotNull(::getFile)
  }

  fun isOnModulePath(file: VirtualFile): Boolean {
    if (file !is FsFile && file !is JarFile) return false
    val paths = modulepaths(file.pklProject)
    return paths.any(file.path::startsWith)
  }
}
