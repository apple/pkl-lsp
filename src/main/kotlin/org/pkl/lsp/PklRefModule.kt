/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp

import org.pkl.lsp.ast.PklClass
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.type.Type

class PklRefModule(project: Project) : Component(project) {
  val module: PklModule?
    get() = project.stdlib.ref?.getModule()?.get()

  val types: Map<String, Type> = buildMap {
    for (member in module?.members ?: emptyList()) {
      if (member is PklClass) {
        put(member.name, Type.Class.create(member))
      }
    }
  }

  // Will be `null` for versions < 0.32
  val referenceType: Type.Reference? by lazy { types["Reference"] as? Type.Reference }
}
