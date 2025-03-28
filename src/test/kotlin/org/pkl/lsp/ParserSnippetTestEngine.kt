/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.reflect.KClass
import org.pkl.lsp.ast.PklModuleImpl
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.Terminal

class ParserSnippetTestEngine : InputOutputTestEngine() {
  override val testClass: KClass<*> = ParserSnippetTests::class

  override val includedTests: List<Regex> = listOf(Regex(".*"))

  @Suppress("RegExpUnexpectedAnchor") override val excludedTests: List<Regex> = listOf(Regex("$^"))

  override val inputDir: Path =
    FileTestUtils.rootProjectDir.resolve("src/test/files/ParserSnippetTests/inputs")

  private val outputDir: Path = inputDir.resolve("../outputs")

  override val isInputFile: (Path) -> Boolean = { it.isRegularFile() && it.extension == "pkl" }

  override fun expectedOutputFileFor(inputFile: Path): Path {
    val relativePath = inputDir.relativize(inputFile)
    return outputDir.resolve(relativePath.toString().dropLast(4) + ".txt")
  }

  private val project = Project(PklLspServer(true))

  private fun PklNode.render(): String {
    return buildString {
      doRender(this)
      appendLine()
    }
  }

  private fun PklNode.doRender(sb: StringBuilder, indent: String = "", isFirst: Boolean = true) {
    if (this is Terminal) return
    if (!isFirst) {
      sb.append("\n")
    }
    sb.append(indent)
    sb.append(this::class.simpleName)
    for (child in this.children) {
      child.doRender(sb, "$indent  ", false)
    }
  }

  override fun generateOutputFor(inputFile: Path): Pair<Boolean, String> {
    return try {
      val text = inputFile.readText()
      val tree = project.pklParser.parse(text)
      val mod = PklModuleImpl(tree.rootNode, project.virtualFileManager.getEphemeral(text))
      true to mod.render()
    } catch (err: Throwable) {
      false to err.stackTraceToString()
    }
  }
}
