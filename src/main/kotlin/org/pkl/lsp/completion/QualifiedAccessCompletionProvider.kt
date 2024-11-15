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
package org.pkl.lsp.completion

import org.eclipse.lsp4j.*
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.*

class QualifiedAccessCompletionProvider(private val project: Project) : CompletionProvider {
  override fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
  ) {
    val line = params.position.line + 1
    val column = params.position.character + 1
    val context = node.containingFile.pklProject
    val actualNode =
      node.enclosingModule?.findBySpan(line, column) as? PklQualifiedAccessExpr ?: return
    val receiverType =
      actualNode.receiverExpr.computeExprType(project.pklBaseModule, mapOf(), context)
    val visitor = ResolveVisitors.completionItems(project.pklBaseModule)
    Resolvers.resolveQualifiedAccess(receiverType, true, project.pklBaseModule, visitor, context)
    Resolvers.resolveQualifiedAccess(receiverType, false, project.pklBaseModule, visitor, context)
    collector.addAll(visitor.result)
  }
}
