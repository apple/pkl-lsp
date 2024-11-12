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
package org.pkl.lsp.util

import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.Version

sealed interface Feature {
  val featureName: String
  val requiredVersion: Version
  val predicate: (PklNode) -> Boolean
  val message: String

  fun isSupported(module: PklModule): Boolean = module.effectivePklVersion >= requiredVersion

  companion object {
    val features: List<Feature> = listOf(ConstObjectMember)

    data object ConstObjectMember : Feature {
      override val featureName: String = "const object member"
      override val requiredVersion: Version = Version.PKL_VERSION_0_27
      override val message: String =
        "Modifier 'const' cannot be applied to object members in this Pkl version."
      override val predicate: (PklNode) -> Boolean = { node ->
        node is Terminal && node.type == TokenType.CONST && node.parent?.parent is PklObjectMember
      }
    }
  }
}
