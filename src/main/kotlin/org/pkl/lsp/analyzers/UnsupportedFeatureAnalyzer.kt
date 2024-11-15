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
package org.pkl.lsp.analyzers

import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.util.Feature

class UnsupportedFeatureAnalyzer(project: Project) : Analyzer(project) {

  override fun doAnalyze(node: PklNode, diagnosticsHolder: MutableList<PklDiagnostic>): Boolean {
    val containingModule = node.enclosingModule ?: return false
    val feature = Feature.features.find { it.predicate(node) } ?: return true
    if (feature.isSupported(containingModule)) return true
    diagnosticsHolder +=
      error(
        node,
        feature.message +
          "\nRequired Pkl version: `${feature.requiredVersion}`. Detected Pkl version: `${containingModule.effectivePklVersion}`",
      )
    return true
  }
}
