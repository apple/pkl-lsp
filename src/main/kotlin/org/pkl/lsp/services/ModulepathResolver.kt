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
package org.pkl.lsp.services

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.*
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ensureJarFileSystem
import org.pkl.lsp.packages.dto.PklProject

class ModulepathResolver(project: Project) : Component(project) {

  private val archivePathMatcher: PathMatcher by lazy {
    FileSystems.getDefault().getPathMatcher("glob:**/*.{zip,jar}")
  }

  private fun fromSettings(): List<Path> {
    return project.settingsManager.settings.modulepath.map(this::normalizeArchivePath)
  }

  private fun fromProject(context: PklProject): List<Path> {
    val modulepath = context.metadata.evaluatorSettings?.modulePath ?: return emptyList()
    return modulepath.map { this.normalizeArchivePath(context.projectDir.resolve(it)) }
  }

  private fun all(context: PklProject?): List<Path> =
    fromSettings() + context?.let(::fromProject).orEmpty()

  private fun normalizeArchivePath(path: Path): Path {
    val path = path.normalize().toAbsolutePath()
    if (isArchive(path)) {
      val uri = URI.create("jar:${path.toUri()}!/")
      ensureJarFileSystem(uri)
      return Paths.get(uri)
    }
    return path
  }

  private fun isArchive(path: Path): Boolean {
    if (!Files.isRegularFile(path)) return false
    if (archivePathMatcher.matches(path)) return true
    val execHeader =
      "#!/bin/sh\n      exec java  -jar $0 \"$@\"".toByteArray(StandardCharsets.UTF_8)
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

  private fun resolve(path: String, modulepath: List<Path>): PklModule? {
    return modulepath
      .asSequence()
      .map { it.resolve(path).normalize() }
      .firstOrNull(Files::exists)
      ?.let { project.virtualFileManager.get(URI.create("modulepath:${it.toUri()}"), it) }
      ?.getModule()
      ?.get()
  }

  fun resolveAbsolute(path: String, context: PklProject?): PklModule? =
    resolve(path.trimStart('/'), all(context))

  fun resolveRelative(sourceFile: VirtualFile, path: String, context: PklProject?): PklModule? {
    val all = all(context)
    val root = all.firstOrNull(sourceFile.path::startsWith) ?: return null
    val relative =
      runCatching { root.relativize(sourceFile.path.parent) }.getOrNull() ?: return null
    return resolve(relative.resolve(path).normalize().toString(), all)
  }

  fun paths(context: PklProject?): List<VirtualFile> =
    all(context).mapNotNull(project.virtualFileManager::get)
}
