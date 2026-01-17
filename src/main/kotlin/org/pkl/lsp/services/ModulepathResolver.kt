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

import java.nio.file.Files
import java.nio.file.Path
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.packages.dto.PklProject

class ModulepathResolver(project: Project) : Component(project) {
  fun resolve(path: String, context: PklProject?): PklModule? {
    val path = path.trimStart('/')
    val file =
      project.settingsManager.settings.modulepath
        .map { it.resolve(path) }
        .firstOrNull(Files::exists)
        ?: context
          ?.metadata
          ?.evaluatorSettings
          ?.modulePath
          ?.map { context.projectDir.resolve(it, path) }
          ?.firstOrNull(Files::exists)
        ?: return null
    return project.virtualFileManager.get(file)?.getModule()?.get()
  }

  fun paths(context: PklProject?): List<Path> {
    val fromSettings = project.settingsManager.settings.modulepath
    val fromProject =
      context?.metadata?.evaluatorSettings?.modulePath?.map(context.projectDir::resolve)
        ?: return fromSettings
    return fromSettings + fromProject
  }
}
