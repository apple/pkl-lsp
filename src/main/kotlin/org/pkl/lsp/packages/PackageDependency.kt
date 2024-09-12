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

import java.nio.file.Files
import java.nio.file.Path
import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.packages.dto.*

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

data class PackageDependency(
  override val packageUri: PackageUri,
  val pklProject: PklProject?,
  val checksums: Checksums?,
) : Dependency {
  override fun getRoot(project: Project): VirtualFile? =
    project.packageManager.getLibraryRoots(this)?.packageRoot
}

data class LocalProjectDependency(
  override val packageUri: PackageUri,
  private val projectDir: Path,
) : Dependency {
  override fun getRoot(project: Project): VirtualFile? = project.virtualFileManager.get(projectDir)
}

fun ResolvedDependency.toDependency(pklProject: PklProject): Dependency? =
  when (this) {
    is LocalDependency -> {
      val localProjectRoot = pklProject.projectDir.resolve(this.path)
      if (Files.exists(localProjectRoot)) {
        LocalProjectDependency(uri, localProjectRoot)
      } else {
        null
      }
    }
    else -> {
      this as RemoteDependency
      PackageDependency(uri, pklProject, this.checksums)
    }
  }
