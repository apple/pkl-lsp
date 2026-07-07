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
package org.pkl.lsp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createParentDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.reflect.KClass
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeConfigurationCapabilities
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.pkl.lsp.commons.SimpleClient

abstract class AbstractHoverSnippetTestsEngine : InputOutputTestEngine() {
  protected abstract fun getHover(inputFile: Path, hoverParams: HoverParams): Hover

  /**
   * Convenience for development; this selects which snippet test(s) to run. There is a
   * (non-snippet) test to make sure this is `""` before commit.
   */
  // language=regexp
  internal val selection: String = ""

  protected val tempDir: Path by lazy { Files.createTempDirectory(javaClass.simpleName) }

  override val includedTests: List<Regex> = listOf(Regex(".*$selection\\.pkl"))

  override val inputDir: Path =
    FileTestUtils.rootProjectDir.resolve("src/test/files/HoverSnippetTests/input")

  private val outputDir: Path = inputDir.resolve("../output")

  override val isInputFile: (Path) -> Boolean = { it.isRegularFile() && it.extension == "pkl" }

  override fun expectedOutputFileFor(inputFile: Path): Path {
    val relativePath = inputDir.relativize(inputFile)
    return outputDir.resolve(relativePath.toString().dropLast(4) + ".txt")
  }

  final override fun generateOutputFor(inputFile: Path): Pair<Boolean, String> {
    val sourceText = inputFile.readText()
    val (contents, position) = normalizeContentsAndCaretPosition(sourceText)
    val relativePath = inputDir.relativize(inputFile)
    val tempFile =
      tempDir.resolve(relativePath).also {
        it.createParentDirectories()
        it.writeText(contents)
      }
    val hoverParams =
      HoverParams().apply {
        this.position = position!!
        this.textDocument = TextDocumentIdentifier(tempFile.toUri().toString())
      }
    val hover = getHover(inputFile, hoverParams)
    return true to render(contents, hoverParams, hover)
  }

  private fun render(sourceCode: String, hoverParams: HoverParams, hover: Hover): String {
    return buildString {
      val lines = sourceCode.lines()
      val position = hoverParams.position
      for ((lineNum, lineText) in lines.withIndex()) {
        append(' ')
        append(lineText)
        if (lineNum < lines.lastIndex) {
          append('\n')
        }
        if (lineNum == position.line) {
          appendHover(hover, hoverParams, lineNum, lineText)
        }
      }
    }
  }

  private fun StringBuilder.appendHover(
    hover: Hover,
    hoverParams: HoverParams,
    lineNum: Int,
    lineText: String,
  ) {
    append(" ".repeat(hoverParams.position.character))
    append("^")
    append('\n')
    for (message in hover.messages()) {
      val lines = message.lines()
      for ((idx, line) in lines.withIndex()) {
        append("| ${line.stripTempDir()}")
        if (idx < lines.lastIndex) {
          append("\n")
        }
      }
    }
  }

  private fun String.stripTempDir(): String {
    return replace(tempDir.toUri().toString(), "\$snippetsDir/")
  }

  private fun Hover.messages(): List<String> {
    return when {
      contents.isLeft ->
        contents.left.map { elem -> if (elem.isLeft) elem.left else elem.right.value }
      else -> listOf(contents.right.value)
    }
  }
}

class HoverSnippetTestsEngine : AbstractHoverSnippetTestsEngine() {
  private val server: PklLspServer by lazy {
    PklLspServer(true).also { it.connect(TestLanguageClient) }
  }

  override fun getHover(inputFile: Path, hoverParams: HoverParams): Hover {
    reset()
    return server.textDocumentService.hover(hoverParams).get()
  }

  override val testClass: KClass<*> = HoverSnippetTests::class

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
  }
}

abstract class AbstractNativeHoverSnippetTestsEngine : AbstractHoverSnippetTestsEngine() {
  abstract val pathToPklLsp: Path

  val client by lazy { SimpleClient() }

  override fun getHover(inputFile: Path, hoverParams: HoverParams): Hover {
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
    return try {
      server.textDocumentService.hover(hoverParams).get()
    } finally {
      server.shutdown().get()
      server.exit()
      serverProcess.waitFor(1, TimeUnit.SECONDS)
    }
  }
}

class MacAarch64HoverSnippetTestsEngine : AbstractNativeHoverSnippetTestsEngine() {
  override val testClass: KClass<*>
    get() = MacHoverSnippetTests::class

  override val pathToPklLsp: Path
    get() = FileTestUtils.rootProjectDir.resolve("build/executable/pkl-lsp-macos-aarch64")
}

class LinuxAarch64HoverSnippetTestsEngine : AbstractNativeHoverSnippetTestsEngine() {
  override val testClass: KClass<*>
    get() = MacHoverSnippetTests::class

  override val pathToPklLsp: Path
    get() = FileTestUtils.rootProjectDir.resolve("build/executable/pkl-lsp-linux-aarch64")
}

class LinuxAmd64HoverSnippetTestsEngine : AbstractNativeHoverSnippetTestsEngine() {
  override val testClass: KClass<*>
    get() = MacHoverSnippetTests::class

  override val pathToPklLsp: Path
    get() = FileTestUtils.rootProjectDir.resolve("build/executable/pkl-lsp-linux-amd64")
}

class WindowsAmd64HoverSnippetTestsEngine : AbstractNativeHoverSnippetTestsEngine() {
  override val testClass: KClass<*>
    get() = MacHoverSnippetTests::class

  override val pathToPklLsp: Path
    get() = FileTestUtils.rootProjectDir.resolve("build/executable/pkl-lsp-windows-amd64.exe")
}
