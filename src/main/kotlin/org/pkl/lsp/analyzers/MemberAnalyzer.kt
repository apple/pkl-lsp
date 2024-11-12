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
package org.pkl.lsp.analyzers

import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.PklLspBugException
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.Version

class MemberAnalyzer(project: Project) : Analyzer(project) {

  override fun doAnalyze(node: PklNode, diagnosticsHolder: MutableList<PklDiagnostic>): Boolean {
    if (node !is PklModifierListOwner) return true
    val module = node.enclosingModule ?: return true

    var localModifier: Terminal? = null
    var abstractModifier: Terminal? = null
    var openModifier: Terminal? = null
    var hiddenModifier: Terminal? = null
    var fixedModifier: Terminal? = null
    var constModifier: Terminal? = null

    for (modifier in node.modifiers ?: listOf()) {
      when (modifier.type) {
        TokenType.LOCAL -> localModifier = modifier
        TokenType.ABSTRACT -> abstractModifier = modifier
        TokenType.OPEN -> openModifier = modifier
        TokenType.HIDDEN -> hiddenModifier = modifier
        TokenType.FIXED -> fixedModifier = modifier
        TokenType.CONST -> constModifier = modifier
        else -> throw PklLspBugException("Unexpected modifier: ${modifier.text}")
      }
    }

    if (module.effectivePklVersion >= Version.PKL_VERSION_0_27) {
      // TODO: add a quick-fix
      if (constModifier != null && localModifier == null && node is PklObjectMember) {
        diagnosticsHolder +=
          error(constModifier, ErrorMessages.create("invalidModifierConstWithoutLocal"))
      }
    }
    return true
  }
}
