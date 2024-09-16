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
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.pkl.lsp.*
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.packages.Dependency
import org.pkl.lsp.packages.PackageDependency
import org.pkl.lsp.packages.dto.PackageMetadata
import org.pkl.lsp.packages.dto.PackageUri
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.packages.toDependency
import org.pkl.lsp.util.CachedValue

data class PackageLibraryRoots(
  val zipFile: VirtualFile,
  val metadataFile: VirtualFile,
  val packageRoot: VirtualFile,
)

val packageTopic = Topic<PackageEvent>("PackageEvent")

sealed interface PackageEvent {
  val packageUri: PackageUri

  data class PackageDownloaded(override val packageUri: PackageUri) : PackageEvent
}

class PackageManager(project: Project) : Component(project) {
  private fun Path.toVirtualFile(): VirtualFile? = project.virtualFileManager.get(this)

  fun getLibraryRoots(dependency: PackageDependency): PackageLibraryRoots? =
    project.cachedValuesManager.getCachedValue("library-roots-${dependency.packageUri}") {
      logger.info("Getting library roots for ${dependency.packageUri}")
      val metadataFile =
        dependency.packageUri.relativeMetadataFiles.firstNotNullOfOrNull {
          pklCacheDir.resolve(it).toVirtualFile()
        }
          ?: run {
            val paths = dependency.packageUri.relativeMetadataFiles.map(pklCacheDir::resolve)
            logger.info("Missing metadata file at paths ${paths.joinToString(", ") { "`$it`" }}")
            return@getCachedValue null
          }
      val zipFile =
        dependency.packageUri.relativeZipFiles.firstNotNullOfOrNull {
          pklCacheDir.resolve(it).toVirtualFile()
        }
          ?: run {
            val paths = dependency.packageUri.relativeZipFiles.map(pklCacheDir::resolve)
            logger.info("Missing zip file at paths ${paths.joinToString(", ") { "`$it`" }}")
            return@getCachedValue null
          }
      val jarRoot = project.virtualFileManager.get(URI("jar:${zipFile.uri}!/"))!!
      CachedValue(PackageLibraryRoots(zipFile, metadataFile, jarRoot))
    }

  fun getResolvedDependencies(
    packageDependency: PackageDependency,
    context: PklProject?,
  ): Map<String, Dependency>? {
    val metadata = getPackageMetadata(packageDependency) ?: return null
    if (packageDependency.pklProject != null) {
      return getResolvedDependenciesOfProjectPackage(packageDependency.pklProject, metadata)
    }
    return metadata.dependencies.mapValues { (_, dep) ->
      if (context != null) {
        val resolvedDep = context.projectDeps?.getResolvedDependency(dep.uri) ?: dep
        resolvedDep.toDependency(context) ?: PackageDependency(dep.uri, null, dep.checksums)
      } else {
        PackageDependency(dep.uri, null, dep.checksums)
      }
    }
  }

  private fun getPackageMetadata(packageDependency: PackageDependency): PackageMetadata? =
    project.cachedValuesManager.getCachedValue(
      "PackageManager.getPackageMetadata(${packageDependency.packageUri})"
    ) {
      val roots = getLibraryRoots(packageDependency) ?: return@getCachedValue null
      CachedValue(PackageMetadata.load(roots.metadataFile))
    }

  private fun getResolvedDependenciesOfProjectPackage(
    pklProject: PklProject,
    metadata: PackageMetadata,
  ): Map<String, Dependency>? {
    val projectDeps = pklProject.projectDeps ?: return null
    return metadata.dependencies.entries.fold(mapOf()) { acc, (name, packageDependency) ->
      val dep =
        projectDeps.getResolvedDependency(packageDependency.uri)?.toDependency(pklProject)
          ?: return@fold acc
      acc.plus(name to dep)
    }
  }

  fun downloadPackage(packageUri: PackageUri): CompletableFuture<Unit> {
    return project.pklCli
      .downloadPackage(listOf(packageUri), pklCacheDir)
      .thenApply {
        project.messageBus.emit(packageTopic, PackageEvent.PackageDownloaded(packageUri))
      }
      .exceptionally { err ->
        project.languageClient.showMessage(
          MessageParams(
            MessageType.Error,
            """
          Failed to download package `$packageUri`.
          
          $err
        """
              .trimIndent(),
          )
        )
      }
  }
}
