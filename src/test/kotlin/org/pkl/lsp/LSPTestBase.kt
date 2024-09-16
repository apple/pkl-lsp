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
package org.pkl.lsp

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.eclipse.lsp4j.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.pkl.core.parser.Parser
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.findBySpan

abstract class LSPTestBase {
  companion object {
    private lateinit var server: PklLSPServer
    private lateinit var parser: Parser
    internal lateinit var fakeProject: Project

    @JvmStatic
    @BeforeAll
    fun beforeAll() {
      server = PklLSPServer(true).also { it.connect(TestLanguageClient) }
      parser = Parser()
      fakeProject = server.project
      System.getProperty("pklExecutable")?.let { executablePath ->
        TestLanguageClient.settings["Pkl" to "pkl.cli.path"] = executablePath
        fakeProject.settingsManager.settings.pklCliPath = Path.of(executablePath)
      }
    }
  }

  @TempDir protected lateinit var testProjectDir: Path

  private var caretPosition: Position? = null

  private var fileInFocus: Path? = null

  private val modules: MutableMap<URI, PklModule> = HashMap()

  @BeforeEach
  open fun beforeEach() {
    server.initialize(
      InitializeParams().apply {
        capabilities =
          ClientCapabilities().apply {
            workspace = WorkspaceClientCapabilities().apply { workspaceFolders = true }
          }
        workspaceFolders =
          listOf(WorkspaceFolder(testProjectDir.toUri().toString(), testProjectDir.name))
      }
    )
    server.initialized(InitializedParams())
    TestLanguageClient.testProjectDir = testProjectDir
    TestLanguageClient.reset()
    fakeProject.initialize().get()
  }

  @AfterEach
  open fun afterEach() {
    server.shutdown()
  }

  protected fun createPklFile(contents: String): Path = createPklFile("main.pkl", contents)

  /**
   * Creates a Pkl file with [contents] in the test project, and also sets the currently active
   * editor to this file's contents.
   *
   * [contents] can possibly contain a cursor position, marked with the `<caret>` symbol. Only the
   * first presence of `<caret>` is interpreted as a cursor; the rest are interpreted verbatim.
   *
   * If this method is called multiple times, the last call determines the active editor.
   */
  protected fun createPklFile(name: String, contents: String): Path {
    val caret = contents.indexOf("<caret>")
    val effectiveContents =
      if (caret == -1) contents else contents.replaceRange(caret, caret + 7, "")
    val file = testProjectDir.resolve(name).also { it.writeText(effectiveContents) }
    // need to trigger this so the LSP knows about this file.
    server.textDocumentService.didOpen(
      DidOpenTextDocumentParams(file.toTextDocument(effectiveContents))
    )
    if (caret > -1) {
      caretPosition = getPosition(effectiveContents, caret)
    }
    fileInFocus = file
    return file
  }

  /**
   * Issues a goto definition command on the position underneath the cursor, and returns the
   * resolved nodes.
   */
  protected fun goToDefinition(): List<PklNode> {
    if (fileInFocus == null)
      throw IllegalStateException(
        "No active Pkl module found in editor. Call `createPklFile` first."
      )
    val params =
      DefinitionParams(TextDocumentIdentifier(fileInFocus!!.toUri().toString()), caretPosition!!)
    val result = server.textDocumentService.definition(params).get()
    val locations =
      if (result.isLeft) result.left
      else result.right.map { Location(it.targetUri, it.targetRange) }
    return locations.map { location ->
      val moduleUri = resolveToRealUri(location.uri)
      val resolvedModule = getOrInsertModule(moduleUri)
      val position = location.range.start
      resolvedModule.findBySpan(position.line + 1, position.character + 1)
        ?: throw IllegalStateException("Failed to find node at position $location")
    }
  }

  protected fun typeText(text: String) {
    if (fileInFocus == null) {
      throw IllegalStateException(
        "No active Pkl module found in editor. Call `createPklFile` first."
      )
    }
    val currentText = fileInFocus!!.readText()
    val idx = caretPosition?.let { currentText.getIndex(it) } ?: (currentText.length - 1)
    val newText = currentText.replaceRange(idx..idx, text)
    Files.writeString(fileInFocus, newText)
    server.textDocumentService.didChange(
      DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(fileInFocus!!.toUri().toString(), 0),
        listOf(TextDocumentContentChangeEvent(newText)),
      )
    )
  }

  protected fun saveFile() {
    if (fileInFocus == null) {
      throw IllegalStateException(
        "No active Pkl module found in editor. Call `createPklFile` first."
      )
    }
    server.textDocumentService.didSave(
      DidSaveTextDocumentParams(TextDocumentIdentifier(fileInFocus!!.toUri().toString()))
    )
  }

  private fun getOrInsertModule(uri: URI): PklModule {
    modules[uri]?.let {
      return it
    }
    val file = fakeProject.virtualFileManager.get(uri)!!
    return file.getModule().get()!!.also { modules[uri] = it }
  }

  private fun resolveToRealUri(uri: String): URI =
    if (uri.startsWith("file:/") && !uri.startsWith("file:///")) Path.of(URI(uri)).toUri()
    else fakeProject.virtualFileManager.get(URI(uri))!!.uri

  private fun Path.toTextDocument(effectiveContents: String): TextDocumentItem =
    TextDocumentItem(toUri().toString(), "pkl", 0, effectiveContents)

  private fun getPosition(contents: String, index: Int): Position {
    var currentPos = 0
    for ((column, line) in contents.lines().withIndex()) {
      val nextPos = currentPos + line.length + 1 // + 1 because newline is also a character
      if (nextPos >= index) {
        val character = index - currentPos
        return Position(column, character)
      }
      currentPos = nextPos
    }
    throw IllegalArgumentException("Invalid index for contents")
  }
}
