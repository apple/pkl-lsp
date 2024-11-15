/*
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
package org.pkl.lsp.actions

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.Command
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.analyzers.PklDiagnostic

sealed interface PklCodeAction {
  val title: String

  val kind: String

  val commandId: String

  val arguments: List<Any>

  val disabled: CodeActionDisabled?

  fun toMessage(diagnostic: PklDiagnostic): CodeAction
}

abstract class PklCommandCodeAction(project: Project) : Component(project), PklCodeAction {
  final override fun toMessage(diagnostic: PklDiagnostic): CodeAction {
    val self = this
    return CodeAction().apply {
      title = self.title
      kind = self.kind
      disabled = self.disabled
      isPreferred = true
      diagnostics = listOf(diagnostic.toMessage())
      command =
        Command().apply {
          title = self.title
          command = self.commandId
          arguments = self.arguments
        }
    }
  }
}
