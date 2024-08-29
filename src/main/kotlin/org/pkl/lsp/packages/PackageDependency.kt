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
package org.pkl.lsp.packages

import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.packages.dto.Checksums
import org.pkl.lsp.packages.dto.PackageUri

/**
 * Either package dependency, or a local project.
 *
 * A package dependency can be a project-relative dependency, where its transitive dependencies are
 * resolved according to a `PklProject.deps.json` file, or an absolute package import, where its
 * transitive dependencies are resolved using its own metadata file.
 */
sealed interface Dependency {
  fun getRoot(project: Project): VirtualFile?

  val packageUri: PackageUri
}

data class PackageDependency(override val packageUri: PackageUri, val checksums: Checksums?) :
  Dependency {
  override fun getRoot(project: Project): VirtualFile? =
    project.packageManager.getLibraryRoots(this)?.packageRoot
}

data class LocalProjectDependency(
  override val packageUri: PackageUri,
  private val projectDir: VirtualFile,
) : Dependency {
  override fun getRoot(project: Project): VirtualFile = projectDir
}
