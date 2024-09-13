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
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.*
import org.pkl.lsp.packages.dto.PackageUri

class PklLSPServer(val verbose: Boolean) : LanguageServer {
  internal val project: Project = Project(this)
  private lateinit var client: PklLanguageClient
  private lateinit var logger: ClientLogger

  private val workspaceService: PklWorkspaceService by lazy { PklWorkspaceService(project) }
  private val textService: PklTextDocumentService by lazy { PklTextDocumentService(project) }

  private lateinit var clientCapabilities: ClientCapabilities
  private var workspaceFolders: List<WorkspaceFolder>? = null

  override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
    clientCapabilities = params.capabilities
    workspaceFolders = params.workspaceFolders
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
    return CompletableFuture.supplyAsync { res }
  }

  override fun initialized(params: InitializedParams) {
    if (clientCapabilities.workspace.workspaceFolders == true) {
      project.pklProjectManager.initialize(workspaceFolders?.map { Path.of(URI(it.uri)) })
    }
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
    project.initialize()
  }

  override fun shutdown(): CompletableFuture<Any> {
    project.dispose()
    return CompletableFuture.completedFuture(Unit)
  }

  override fun exit() {
    exitProcess(0)
  }

  override fun getTextDocumentService(): TextDocumentService = textService

  override fun getWorkspaceService(): WorkspaceService = workspaceService

  override fun setTrace(params: SetTraceParams?) {
    // noop
  }

  fun client(): PklLanguageClient = client

  fun connect(client: PklLanguageClient) {
    this.client = client
    logger = project.getLogger(this::class)
    logger.log("Starting Pkl LSP Server")
  }

  @Suppress("unused")
  @JsonRequest(value = "pkl/fileContents")
  fun fileContentsRequest(param: TextDocumentIdentifier): CompletableFuture<String> {
    return CompletableFuture.supplyAsync {
      val uri = URI.create(param.uri)
      project.virtualFileManager.get(uri)?.contents ?: ""
    }
  }

  @Suppress("unused")
  @JsonRequest(value = "pkl/downloadPackage")
  fun downloadPackage(param: String): CompletableFuture<Unit> {
    val packageUri = PackageUri.create(param)!!
    return project.packageManager.downloadPackage(packageUri)
  }

  @Suppress("unused")
  @JsonRequest(value = "pkl/syncProjects")
  fun syncProjects(@Suppress("UNUSED_PARAMETER") ignored: Any?): CompletableFuture<Unit> {
    return project.pklProjectManager.syncProjects(true)
  }
}
