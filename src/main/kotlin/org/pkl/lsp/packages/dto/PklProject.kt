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
package org.pkl.lsp.packages.dto

import java.net.URI
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.packages.Dependency
import org.pkl.lsp.packages.toDependency
import org.pkl.lsp.util.URISerializer

data class PklProject(val metadata: DerivedProjectMetadata, val projectDeps: ProjectDeps?) {

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseMetadata(input: String): List<DerivedProjectMetadata> {
      return json.decodeFromString(input)
    }

    private fun parseDeps(input: String): ProjectDeps {
      return json.decodeFromString(input)
    }

    fun loadProjectDeps(file: VirtualFile): ProjectDeps {
      return parseDeps(file.contents)
    }

    @Serializable
    data class DerivedProjectMetadata(
      @Serializable(with = URISerializer::class) val projectFileUri: URI,
      val packageUri: PackageUri?,
      val declaredDependencies: Map<String, PackageUri>,
      val evaluatorSettings: EvaluatorSettings?,
    )

    @Serializable data class EvaluatorSettings(val moduleCacheDir: String? = null)

    @Serializable
    data class ProjectDeps(
      val schemaVersion: Int,
      val resolvedDependencies: Map<String, ResolvedDependency>,
    ) {
      /** Given a package URI, return the resolved dependency for it. */
      fun getResolvedDependency(packageUri: PackageUri): ResolvedDependency? {
        val packageUriStr = packageUri.toString()
        return resolvedDependencies.entries.find { packageUriStr.startsWith(it.key) }?.value
      }
    }
  }

  val projectFile: Path by lazy { Path.of(metadata.projectFileUri) }

  val projectDir: Path by lazy { projectFile.parent }

  /** The dependencies declared within the PklProject file */
  val myDependencies: Map<String, Dependency>
    get() {
      return metadata.declaredDependencies.entries.fold(mapOf()) { acc, (name, packageUri) ->
        val dep =
          projectDeps?.getResolvedDependency(packageUri)?.toDependency(this) ?: return@fold acc
        acc.plus(name to dep)
      }
    }

  fun getResolvedDependencies(context: PklProject?): Map<String, Dependency> {
    if (context == null || context === this) return myDependencies
    return myDependencies.mapValues { (_, dep) ->
      // best-effort approach; if the dependency is not found in [context], show what's found in
      // [myDependencies].
      context.projectDeps?.getResolvedDependency(dep.packageUri)?.toDependency(context) ?: dep
    }
  }
}
