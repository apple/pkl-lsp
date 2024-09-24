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
package org.pkl.lsp.completion

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklUnqualifiedAccessExpr
import org.pkl.lsp.ast.findBySpan
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers

class UnqualifiedAccessCompletionProvider(private val project: Project) : CompletionProvider {

  override fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
  ) {
    val line = params.position.line + 1
    val column = params.position.character + 1
    val context = node.containingFile.pklProject
    val actualNode =
      node.enclosingModule?.findBySpan(line, column) as? PklUnqualifiedAccessExpr ?: return
    val visitor = ResolveVisitors.completionItems(project.pklBaseModule, actualNode.text)
    Resolvers.resolveUnqualifiedAccess(
      actualNode,
      null,
      isProperty = true,
      project.pklBaseModule,
      mapOf(),
      visitor,
      context,
    )
    Resolvers.resolveUnqualifiedAccess(
      actualNode,
      null,
      isProperty = false,
      project.pklBaseModule,
      mapOf(),
      visitor,
      context,
    )
    collector.addAll(visitor.result)
  }
}
