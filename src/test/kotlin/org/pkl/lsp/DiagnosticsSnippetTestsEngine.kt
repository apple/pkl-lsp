/*
 * Copyright © 2025-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.reflect.KClass
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeConfigurationCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.pkl.lsp.commons.SimpleClient

abstract class AbstractDiagnosticsSnippetTestEngine : InputOutputTestEngine() {
  /**
   * Convenience for development; this selects which snippet test(s) to run. There is a
   * (non-snippet) test to make sure this is `""` before commit.
   */
  // language=regexp
  internal val selection: String = ""

  override val includedTests: List<Regex> = listOf(Regex(".*$selection\\.pkl"))

  override val inputDir: Path =
    FileTestUtils.rootProjectDir.resolve("src/test/files/DiagnosticsSnippetTests/input")

  private val outputDir: Path = inputDir.resolve("../output")

  override val isInputFile: (Path) -> Boolean = { it.isRegularFile() && it.extension == "pkl" }

  override fun expectedOutputFileFor(inputFile: Path): Path {
    val relativePath = inputDir.relativize(inputFile)
    return outputDir.resolve(relativePath.toString().dropLast(4) + ".txt")
  }

  final override fun generateOutputFor(inputFile: Path): Pair<Boolean, String> {
    val sourceText = inputFile.readText()
    val diagnostics = getDiagnostics(inputFile)
    return true to render(sourceText, diagnostics)
  }

  abstract fun getDiagnostics(inputFile: Path): List<Diagnostic>

  protected fun render(sourceCode: String, diagnostics: List<Diagnostic>): String {
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

  private fun getDiagnostics(diagnostics: List<Diagnostic>, lineNum: Int): List<Diagnostic> {
    return diagnostics.filter { it.range.start.line <= lineNum && it.range.end.line >= lineNum }
  }

  private fun StringBuilder.appendDiagnostic(
    diagnostic: Diagnostic,
    lineNum: Int,
    lineText: String,
  ) {
    when {
      lineNum > diagnostic.range.start.line && lineNum < diagnostic.range.end.line -> {
        append(' ')
        appendLine("^".repeat(lineText.length))
        append('\n')
      }
      lineNum == diagnostic.range.start.line && lineNum < diagnostic.range.end.line -> {
        append(" ".repeat(diagnostic.range.start.character + 1))
        append("^".repeat(lineText.length - diagnostic.range.start.character))
        append('\n')
      }
      else -> {
        append(" ".repeat(diagnostic.range.start.character + 1))
        append("^".repeat(diagnostic.range.end.character - diagnostic.range.start.character))
        append('\n')
      }
    }
    if (diagnostic.range.end.line == lineNum) {
      val message =
        when {
          diagnostic.message.isLeft -> diagnostic.message.left
          else -> diagnostic.message.right.value
        }
      val effectiveMessage = "${diagnostic.severity}: $message"
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

class DiagnosticsSnippetTestsEngine : AbstractDiagnosticsSnippetTestEngine() {
  override val testClass: KClass<*>
    get() = DiagnosticsSnippetTests::class

  val server: PklLspServer by lazy { PklLspServer(true).also { it.connect(TestLanguageClient) } }

  private val fakeProject by lazy {
    val project = server.project
    System.getProperty("pklExecutable")?.let { executablePath ->
      TestLanguageClient.settings["Pkl" to "pkl.cli.path"] = executablePath
      project.settingsManager.update { it.copy(pklCliPath = Path.of(executablePath)) }
    }
    project
  }

  override fun getDiagnostics(inputFile: Path): List<Diagnostic> {
    reset()
    fakeProject.messageBus.emit(textDocumentTopic, TextDocumentEvent.Opened(inputFile.toUri()))
    val module = fakeProject.virtualFileManager.get(inputFile)!!.getModule().get()!!
    val diagnostics = fakeProject.diagnosticsManager.getDiagnostics(module)
    return diagnostics.map { it.toMessage() }
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
}

abstract class AbstractNativeDiagnosticsSnippetTestEngine : AbstractDiagnosticsSnippetTestEngine() {
  abstract val pathToPklLsp: Path

  val client by lazy { SimpleClient() }

  override fun getDiagnostics(inputFile: Path): List<Diagnostic> {
    val serverProcess =
      with(ProcessBuilder()) {
        command(pathToPklLsp.absolutePathString())
        redirectError(ProcessBuilder.Redirect.INHERIT)
        start()
      }
    val launcher =
      LSPLauncher.createClientLauncher(
        client,
        serverProcess.inputStream,
        serverProcess.outputStream,
      )
    launcher.startListening()
    val server = launcher.remoteProxy

    val rootUri = inputFile.parent.toUri()
    server
      .initialize(
        InitializeParams().apply {
          setRootUri(rootUri.toString())
          workspaceFolders = listOf(WorkspaceFolder(rootUri.toString(), "diagnostic-snippet-test"))
          capabilities =
            ClientCapabilities().apply {
              workspace =
                WorkspaceClientCapabilities().apply {
                  workspaceFolders = true
                  didChangeConfiguration = DidChangeConfigurationCapabilities(false)
                }
            }
        }
      )
      .get()
    server.initialized(InitializedParams())
    server.textDocumentService.didOpen(
      DidOpenTextDocumentParams(
        TextDocumentItem(inputFile.toUri().toString(), "pkl", 1, inputFile.readText())
      )
    )
    val publishDiagnosticParams = client.getNextDiagnostic()
    server.shutdown().get()
    server.exit()
    serverProcess.waitFor(1, TimeUnit.SECONDS)
    return publishDiagnosticParams.diagnostics
  }
}

class MacAarch64DiagnosticsSnippetTestsEngine : AbstractNativeDiagnosticsSnippetTestEngine() {
  override val testClass: KClass<*>
    get() = MacDiagnosticSnippetTests::class

  override val pathToPklLsp: Path
    get() = FileTestUtils.rootProjectDir.resolve("build/executable/pkl-lsp-macos-aarch64")
}

class LinuxAmd64DiagnosticsSnippetTestsEngine : AbstractNativeDiagnosticsSnippetTestEngine() {
  override val testClass: KClass<*> = LinuxDiagnosticSnippetTests::class

  override val pathToPklLsp: Path
    get() = FileTestUtils.rootProjectDir.resolve("build/executable/pkl-lsp-linux-amd64")
}

class LinuxAarch64DiagnosticsSnippetTestsEngine : AbstractNativeDiagnosticsSnippetTestEngine() {
  override val testClass: KClass<*> = LinuxDiagnosticSnippetTests::class

  override val pathToPklLsp: Path
    get() = FileTestUtils.rootProjectDir.resolve("build/executable/pkl-lsp-linux-aarch64")
}

class WindowsAmd64DiagnosticsSnippetTestsEngine : AbstractNativeDiagnosticsSnippetTestEngine() {
  override val testClass: KClass<*> = WindowsDiagnosticSnippetTests::class

  override val pathToPklLsp: Path
    get() = FileTestUtils.rootProjectDir.resolve("build/executable/pkl-lsp-windows-amd64.exe")
}
