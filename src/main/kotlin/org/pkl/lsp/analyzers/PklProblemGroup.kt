/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.analyzers

import org.pkl.lsp.actions.PklCodeAction
import org.pkl.lsp.actions.PklSuppressWarningsQuickFix
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklSuppressWarningsTarget
import org.pkl.lsp.ast.parentsOfType

class PklProblemGroup(val problemName: String) {
  fun getSuppressQuickFixes(node: PklNode): List<PklCodeAction> {
    if (!node.containingFile.canModify()) return listOf()
    val self = this
    return buildList {
      for (target in node.parentsOfType<PklSuppressWarningsTarget>()) {
        add(PklSuppressWarningsQuickFix(target, self))
      }
    }
  }
}
