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
package org.pkl.lsp.services

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.pkl.lsp.*
import org.pkl.lsp.packages.Dependency
import org.pkl.lsp.packages.PackageDependency
import org.pkl.lsp.packages.dto.PackageMetadata
import org.pkl.lsp.util.CachedValue
import org.pkl.lsp.util.FileCacheManager.Companion.pklCacheDir

data class PackageLibraryRoots(
  val zipFile: VirtualFile,
  val metadataFile: VirtualFile,
  val packageRoot: VirtualFile,
)

class PackageManager(project: Project) : Component(project) {
  private fun Path.toFsFile(): VirtualFile? =
    if (Files.exists(this)) FsFile(this, project) else null

  fun getLibraryRoots(dependency: PackageDependency): PackageLibraryRoots? =
    project.cachedValuesManager.getCachedValue("library-roots-${dependency.packageUri}") {
      logger.info("Getting library roots for ${dependency.packageUri}")
      if (!Files.isDirectory(pklCacheDir)) {
        return@getCachedValue null
      }
      val metadataFile =
        dependency.packageUri.relativeMetadataFiles.firstNotNullOfOrNull {
          pklCacheDir.resolve(it).toFsFile()
        }
          ?: run {
            val paths = dependency.packageUri.relativeMetadataFiles.map(pklCacheDir::resolve)
            logger.info("Missing metadata file at paths ${paths.joinToString(", ") { "`$it`" }}")
            return@getCachedValue null
          }
      val zipFile =
        dependency.packageUri.relativeZipFiles.firstNotNullOfOrNull {
          pklCacheDir.resolve(it).toFsFile()
        }
          ?: run {
            val paths = dependency.packageUri.relativeZipFiles.map(pklCacheDir::resolve)
            logger.info("Missing zip file at paths ${paths.joinToString(", ") { "`$it`" }}")
            return@getCachedValue null
          }
      val jarRoot = JarFile(URI("jar:${zipFile.uri}!/"), project)
      CachedValue(PackageLibraryRoots(zipFile, metadataFile, jarRoot))
    }

  fun getResolvedDependencies(packageDependency: PackageDependency): Map<String, Dependency>? {
    val metadata = getPackageMetadata(packageDependency) ?: return null
    return metadata.dependencies.mapValues { (_, dep) -> PackageDependency(dep.uri, dep.checksums) }
  }

  private fun getPackageMetadata(packageDependency: PackageDependency): PackageMetadata? =
    project.cachedValuesManager.getCachedValue(
      "PackageManager.getPackageMetadata(${packageDependency.packageUri})"
    ) {
      val roots = getLibraryRoots(packageDependency) ?: return@getCachedValue null
      CachedValue(PackageMetadata.load(roots.metadataFile))
    }
}
