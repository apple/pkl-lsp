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
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.*
import org.pkl.core.util.IoUtils
import org.pkl.lsp.packages.dto.PackageUri

class PklLSPServer(val verbose: Boolean) : LanguageServer, LanguageClientAware {
  private val project: Project = Project(this)
  private lateinit var client: LanguageClient
  private lateinit var logger: ClientLogger

  private val workspaceService: PklWorkspaceService by lazy { PklWorkspaceService(project) }
  private val textService: PklTextDocumentService by lazy { PklTextDocumentService(this, project) }

  private val builder: Builder by lazy { Builder(this, project) }

  private val cacheDir: Path = Files.createTempDirectory("pklLSP")
  private val stdlibDir = cacheDir.resolve("stdlib")
  private lateinit var clientCapabilities: ClientCapabilities

  override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
    clientCapabilities = params.capabilities
    val res =
      InitializeResult(ServerCapabilities()).apply {
        capabilities.textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

        // hover capability
        capabilities.setHoverProvider(true)
        // go to definition capability
        capabilities.definitionProvider = Either.forLeft(true)
        // auto completion capability
        capabilities.completionProvider = CompletionOptions(false, listOf("."))
        capabilities.setCodeActionProvider(CodeActionOptions(listOf(CodeActionKind.QuickFix)))
        capabilities.workspace =
          WorkspaceServerCapabilities().apply {
            workspaceFolders =
              WorkspaceFoldersOptions().apply { changeNotifications = Either.forRight(true) }
          }
      }

    // cache the stdlib, so we can open it in the client
    CompletableFuture.supplyAsync(::cacheStdlib)

    return CompletableFuture.supplyAsync { res }
  }

  override fun initialized(params: InitializedParams) {
    project.settingsManager.loadSettings()
    // listen for configuration changes
    if (clientCapabilities.workspace.didChangeConfiguration?.dynamicRegistration == true) {
      client.registerCapability(
        RegistrationParams(
          listOf(
            Registration("didChangeConfigurationRegistration", "workspace/didChangeConfiguration")
          )
        )
      )
    }
  }

  override fun shutdown(): CompletableFuture<Any> {
    return CompletableFuture.supplyAsync(::Object)
  }

  override fun exit() {
    exitProcess(0)
  }

  override fun getTextDocumentService(): TextDocumentService = textService

  override fun getWorkspaceService(): WorkspaceService = workspaceService

  override fun setTrace(params: SetTraceParams?) {
    // noop
  }

  fun builder(): Builder = builder

  fun client(): LanguageClient = client

  override fun connect(client: LanguageClient) {
    this.client = client
    logger = project.getLogger(this::class)
    logger.log("Starting Pkl LSP Server")
  }

  @Suppress("unused")
  @JsonRequest(value = "pkl/fileContents")
  fun fileContentsRequest(param: TextDocumentIdentifier): CompletableFuture<String> {
    return CompletableFuture.supplyAsync {
      val uri = URI.create(param.uri)
      logger.log("parsed uri: $uri")
      VirtualFile.fromUri(uri, project)?.contents() ?: ""
    }
  }

  @Suppress("unused")
  @JsonRequest(value = "pkl/downloadPackage")
  fun downloadPackage(param: String): CompletableFuture<Unit> {
    val packageUri = PackageUri.create(param)!!
    return project.pklCli
      .downloadPackage(listOf(packageUri))
      .thenApply {
        project.workspaceState.openFiles.forEach { uri ->
          val virtualFile = VirtualFile.fromUri(uri, project) ?: return@forEach
          // refresh diagnostics for every module
          builder().requestBuild(uri, virtualFile)
        }
      }
      .exceptionally { err ->
        client.showMessage(
          MessageParams(
            MessageType.Error,
            """
          Failed to download package `$packageUri`.
          
          $err
        """
              .trimIndent(),
          )
        )
      }
  }

  private fun cacheStdlib() {
    stdlibDir.toFile().mkdirs()
    for ((name, _) in project.stdlib.allModules()) {
      val file = stdlibDir.resolve("$name.pkl")
      val text = IoUtils.readClassPathResourceAsString(javaClass, "/org/pkl/core/stdlib/$name.pkl")
      Files.writeString(file, text, Charsets.UTF_8)
    }
  }
}
