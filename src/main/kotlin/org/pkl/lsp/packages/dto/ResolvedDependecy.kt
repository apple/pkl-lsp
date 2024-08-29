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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ResolvedDependency {
  abstract val type: DependencyType
  abstract val uri: PackageUri
}

@Serializable
@SerialName("local")
data class LocalDependency(override val uri: PackageUri, val path: String) : ResolvedDependency() {
  override val type: DependencyType = DependencyType.LOCAL
}

@Serializable
@SerialName("remote")
data class RemoteDependency(override val uri: PackageUri, val checksums: Checksums?) :
  ResolvedDependency() {
  override val type: DependencyType = DependencyType.REMOTE
}

@Serializable
enum class DependencyType(val strValue: String) {
  @SerialName("local") LOCAL("local"),
  @SerialName("remote") REMOTE("remote"),
}
