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
    val path = path.normalize()
    if (Files.isRegularFile(path) && archivePathMatcher.matches(path)) {
      val uri = URI.create("jar:${path.toUri()}!/")
      ensureJarFileSystem(uri)
      return Paths.get(uri)
    }
    return path
  }

  fun resolve(path: String, context: PklProject?): PklModule? {
    val path = path.trimStart('/')
    return all(context)
      .asSequence()
      .map { it.resolve(path) }
      .firstOrNull(Files::exists)
      ?.let(project.virtualFileManager::get)
      ?.getModule()
      ?.get()
  }

  fun paths(context: PklProject?): List<VirtualFile> =
    all(context).mapNotNull(project.virtualFileManager::get)
}
