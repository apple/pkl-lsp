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

import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.pkl.lsp.VirtualFile

@Suppress("PROVIDED_RUNTIME_TOO_LOW")
@Serializable
data class PackageMetadata(
  val dependencies: Map<String, RemoteDependency>,
  val name: String,
  val packageZipUrl: String,
  val version: String,
  val packageUri: PackageUri,
) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(input: VirtualFile): PackageMetadata {
      return parse(input.contents)
    }

    fun load(input: Path): PackageMetadata {
      return parse(input.readText())
    }

    private fun parse(input: String): PackageMetadata {
      return json.decodeFromString(input)
    }
  }
}
