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
package org.pkl.lsp.completion

import java.nio.file.Path
import javax.naming.OperationNotSupportedException
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.ast.PklError
import org.pkl.lsp.ast.PklModuleClause
import org.pkl.lsp.ast.PklModuleHeader
import org.pkl.lsp.ast.PklModuleImpl
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklQualifiedIdentifier
import org.pkl.lsp.inferImportPropertyName
import org.pkl.parser.Lexer

class ModuleNameCompletionProvider(project: Project) : Component(project), CompletionProvider {

  override fun getCompletions(
    node: PklNode,
    params: CompletionParams,
    collector: MutableList<CompletionItem>,
  ) {
    val (completeNames, prefix) =
      if (
        node is PklModuleImpl &&
          ((node.children.singleOrNull() as? PklError)?.text == "module" ||
            (node.children.lastOrNull() as? PklModuleHeader)?.text?.endsWith("module") == true)
      ) {
        emptyList<String>() to null
      } else if ((node as? PklQualifiedIdentifier)?.parent is PklModuleClause) {
        if (params.position != Position(node.span.endLine - 1, node.span.endCol - 1)) return
        node.identifierNames.dropLast(1) to node.identifierNames.lastOrNull()
      } else if (node is PklError && node.text == ".") {
        val modHeader = node.prevSibling() as? PklModuleHeader ?: return
        val name = modHeader.moduleClause?.qualifiedIdentifier ?: return
        // no col offset-by-one here b/c `node` has length 1
        if (params.position != Position(name.span.endLine - 1, name.span.endCol)) return
        name.identifierNames to null
      } else {
        return
      }
    val file = node.containingFile
    val inPackage = completePackagePath(prefix, completeNames, file, collector)
    if (!inPackage) {
      completeFilename(prefix, completeNames, file, collector)
    }
  }

  private fun completePackagePath(
    prefix: String?,
    completeNames: List<String>,
    file: VirtualFile,
    result: MutableList<CompletionItem>,
  ): Boolean {
    // must be in project
    val proj = file.pklProject ?: return false
    // must be in local fs (so relative to project dir)
    val filePath =
      try {
        file.path
      } catch (_: OperationNotSupportedException) {
        return false
      }
    // must have package name metadata
    val packageName = proj.metadata.packageName?.split('.') ?: return false
    val relativeModuleName = filePath.relativeTo(proj.projectDir).splitToModuleNameParts()

    // given package foo.bar
    // module at baz/qux.pkl
    if (completeNames.size < packageName.size) {
      // complete f -> foo.bar.baz.qux
      // complete foo. -> foo.bar.baz.qux

      if (
        completeNames.isNotEmpty() &&
          packageName.take(completeNames.size - 1) != completeNames.dropLast(1)
      )
        return true
      val remainingPackageName = packageName.drop(completeNames.size)
      if (prefix != null && remainingPackageName.firstOrNull()?.startsWith(prefix) == false)
        return true
      result.addResultPath(remainingPackageName + relativeModuleName)
    } else {
      // complete foo.bar. -> foo.bar.baz.qux
      // complete foo.bar.baz. -> foo.bar.baz.qux

      if (completeNames.take(packageName.size) != packageName) return true
      val remainingPathName = relativeModuleName.drop(completeNames.size - packageName.size)
      if (prefix != null && remainingPathName.firstOrNull()?.startsWith(prefix) != true) return true
      result.addResultPath(remainingPathName)
    }
    return true
  }

  private fun completeFilename(
    prefix: String?,
    completeNames: List<String>,
    file: VirtualFile,
    result: MutableList<CompletionItem>,
  ) {
    // must be in first qualified identifier segment
    if (completeNames.isNotEmpty()) return
    val filename = file.name
    val inferredName = inferImportPropertyName(filename) ?: return
    if (prefix?.let { filename.startsWith(it) } == false) return
    result.addResultPath(listOf(inferredName))
  }

  private fun Path.splitToModuleNameParts(): List<String> = mapIndexed { index, part ->
    if (index == nameCount - 1) inferImportPropertyName(part.name)!! else part.name
  }

  private fun MutableList<CompletionItem>.addResultPath(names: List<String>) {
    add(
      CompletionItem().apply {
        kind = CompletionItemKind.Text
        label = names.joinToString(".") { Lexer.maybeQuoteIdentifier(it) }
        insertText = label
      }
    )
  }
}
