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
package org.pkl.lsp.analyzers

import org.eclipse.lsp4j.DiagnosticSeverity
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklObjectEntry
import org.pkl.lsp.ast.PklProperty
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.services.externalreader.ReaderKind

/**
 * Warns when a `PklProject` declares an external module or resource reader that doesn't work: the
 * executable can't be started, it isn't an external reader (it closes early or answers with
 * something unintelligible), or it doesn't support the scheme it's declared for.
 *
 * Finding that out means starting the reader and asking it, which blocks on a child process. That's
 * why this isn't an [Analyzer]: it doesn't take part in `DiagnosticsManager.getDiagnostics`, and is
 * run by the diagnostics manager when projects get synced instead.
 */
class ExternalReaderDeclarationAnalyzer(project: Project) : Component(project) {
  private class Declaration(val scheme: String, val kind: ReaderKind, val node: PklNode)

  /**
   * Checks every reader that [module] declares, using the settings in [context] (the synced
   * `PklProject` that [module] is the source of).
   *
   * Blocks on reader processes; don't call this where time matters.
   */
  fun analyze(module: PklModule, context: PklProject): List<PklDiagnostic> {
    val holder = DiagnosticsHolder()
    val service = project.externalReaderService
    for (declaration in findDeclaredReaders(module)) {
      // Checking whether the reader works is expensive, so skip it if the warning is suppressed
      // anyway (adding a suppressed diagnostic to the holder is a no-op).
      val probe =
        PklDiagnostic(
          declaration.node.span,
          "",
          DiagnosticSeverity.Warning,
          problemGroup = PklProblemGroups.externalReaderError,
        )
      if (probe.isSuppressed(declaration.node)) continue
      val problem =
        service
          .checkProjectReader(declaration.scheme, declaration.kind, context)
          ?.problem(declaration.scheme) ?: continue
      holder.addWarning(declaration.node, problem) {
        problemGroup = PklProblemGroups.externalReaderError
      }
    }
    return holder.diagnostics
  }

  /**
   * The entries of `evaluatorSettings.externalModuleReaders` and `.externalResourceReaders`: the
   * scheme, and the node of its key.
   */
  private fun findDeclaredReaders(module: PklModule): List<Declaration> {
    val result = mutableListOf<Declaration>()
    for (settings in module.properties.filter { it.name == "evaluatorSettings" }) {
      for (readers in settings.descendantsOfType<PklProperty>()) {
        val kind = ReaderKind.entries.find { it.settingName == readers.name } ?: continue
        for (entry in readers.descendantsOfType<PklObjectEntry>()) {
          // only the readers' own entries, not entries of something nested inside of them
          if (entry.parentOfTypes(PklProperty::class) != readers) continue
          val key = entry.keyExpr ?: continue
          val scheme = key.text.removeSurrounding("\"")
          if (scheme.isEmpty() || scheme == key.text) continue // not a plain string literal
          result.add(Declaration(scheme, kind, key))
        }
      }
    }
    return result
  }

  private inline fun <reified T : PklNode> PklNode.descendantsOfType(): List<T> =
    descendants().filterIsInstance<T>().toList()

  private fun PklNode.descendants(): Sequence<PklNode> = sequence {
    for (child in children) {
      yield(child)
      yieldAll(child.descendants())
    }
  }
}
