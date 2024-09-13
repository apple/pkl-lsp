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
package org.pkl.lsp.documentation

import org.pkl.lsp.ast.PklTypeName
import org.pkl.lsp.type.Type

interface TypeNameRenderer {
  fun render(name: PklTypeName, appendable: Appendable)

  fun render(type: Type.Class, appendable: Appendable)

  fun render(type: Type.Alias, appendable: Appendable)

  fun render(type: Type.Module, appendable: Appendable)
}

object DefaultTypeNameRenderer : TypeNameRenderer {
  override fun render(name: PklTypeName, appendable: Appendable) {
    appendable.append(name.simpleTypeName.identifier?.text)
  }

  override fun render(type: Type.Class, appendable: Appendable) {
    appendable.append(type.ctx.name)
  }

  override fun render(type: Type.Alias, appendable: Appendable) {
    appendable.append(type.ctx.name)
  }

  override fun render(type: Type.Module, appendable: Appendable) {
    appendable.append(type.referenceName)
  }
}
