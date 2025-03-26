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
package org.pkl.lsp

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.reflect.KClass
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.pkl.lsp.analyzers.PklDiagnostic
import org.pkl.lsp.ast.PklModule

class DiagnosticsSnippetTestEngine : InputOutputTestEngine() {
  override val testClass: KClass<*> = DiagnosticsSnippetTests::class

  val server: PklLspServer by lazy { PklLspServer(true).also { it.connect(TestLanguageClient) } }

  private val fakeProject by lazy {
    val project = server.project
    System.getProperty("pklExecutable")?.let { executablePath ->
      TestLanguageClient.settings["Pkl" to "pkl.cli.path"] = executablePath
      println("pkl is: $executablePath")
      project.settingsManager.settings.pklCliPath = Path.of(executablePath)
    }
    project
  }

  /**
   * Convenience for development; this selects which snippet test(s) to run. There is a
   * (non-snippet) test to make sure this is `""` before commit.
   */
  // language=regexp
  internal val selection: String = ""

  override val includedTests: List<Regex> = listOf(Regex(".*$selection\\.pkl"))

  override val inputDir: Path =
    FileTestUtils.rootProjectDir.resolve("src/test/files/DiagnosticsSnippetTests/inputs")

  private val outputDir: Path = inputDir.resolve("../outputs")

  override val isInputFile: (Path) -> Boolean = { it.isRegularFile() && it.extension == "pkl" }

  override fun expectedOutputFileFor(inputFile: Path): Path {
    val relativePath = inputDir.relativize(inputFile)
    return outputDir.resolve(relativePath.toString().dropLast(4) + ".txt")
  }

  private fun parse(code: String): PklModule {
    val file = EphemeralFile(code, fakeProject)
    return file.getModule().get()!!
  }

  private fun reset() {
    server.initialize(
      InitializeParams().apply {
        capabilities =
          ClientCapabilities().apply {
            workspace = WorkspaceClientCapabilities().apply { workspaceFolders = true }
          }
      }
    )
    server.initialized(InitializedParams())
    TestLanguageClient.reset()
    fakeProject.initialize().get()
    fakeProject.cachedValuesManager.clearAll()
  }

  override fun generateOutputFor(inputFile: Path): Pair<Boolean, String> {
    reset()
    val sourceText = inputFile.readText()
    val module = parse(sourceText)
    val diagnostics = fakeProject.diagnosticsManager.getDiagnostics(module)
    return true to render(sourceText, diagnostics)
  }

  private fun render(sourceCode: String, diagnostics: List<PklDiagnostic>): String {
    return buildString {
      val lines = sourceCode.lines()
      for ((lineNum, lineText) in lines.withIndex()) {
        val myDiagnostics = getDiagnostics(diagnostics, lineNum)
        if (myDiagnostics.isNotEmpty()) {
          append(' ')
          appendLine(lineText)
          for ((idx, diag) in myDiagnostics.withIndex()) {
            if (idx > 0) {
              append('\n')
            }
            appendDiagnostic(diag, lineNum, lineText)
          }
          if (lineNum < lines.lastIndex) {
            append('\n')
          }
        } else {
          append(' ')
          append(lineText)
          if (lineNum < lines.lastIndex) {
            append('\n')
          }
        }
      }
    }
  }

  private fun getDiagnostics(diagnostics: List<PklDiagnostic>, lineNum: Int): List<PklDiagnostic> {
    // spans are 1-indexed
    val spanLine = lineNum + 1
    return diagnostics.filter { it -> it.span.beginLine <= spanLine && it.span.endLine >= spanLine }
  }

  private fun StringBuilder.appendDiagnostic(
    diagnostic: PklDiagnostic,
    lineNum: Int,
    lineText: String,
  ) {
    val span = diagnostic.span
    val spanLine = lineNum + 1
    when {
      spanLine > span.beginLine && spanLine < span.endLine -> {
        append(' ')
        appendLine("^".repeat(lineText.length))
        append('\n')
      }
      spanLine == span.beginLine && spanLine < span.endLine -> {
        append(" ".repeat(span.beginCol))
        append("^".repeat(lineText.length - span.beginCol - 1))
        append('\n')
      }
      else -> {
        append(" ".repeat(span.beginCol))
        append("^".repeat(span.endCol - span.beginCol))
        append('\n')
      }
    }
    if (span.endLine == spanLine) {
      val effectiveMessage = "${diagnostic.severity}: ${diagnostic.message}"
      val lines = effectiveMessage.lines().filter { !it.isBlank() }
      for ((idx, line) in lines.withIndex()) {
        append("| $line")
        if (idx < lines.lastIndex) {
          append('\n')
        }
      }
    }
  }
}
