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
package org.pkl.lsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.io.FileOutputStream
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.pkl.lsp.PklLspServer
import org.pkl.lsp.Project
import org.pkl.lsp.scip.ScipAnalyzer

class ScipCommand : CliktCommand(name = "scip") {

  private val outputFile: Path by
    option("--output", "-o", help = "Output file for SCIP index (default: index.scip)")
      .path()
      .default(Path.of("index.scip"))

  private val sourceRoots: List<Path> by
    argument(help = "Source directories or files to index")
      .path(mustExist = true)
      .multiple(required = true)

  override fun help(context: Context): String =
    "Generate SCIP (Source Code Intelligence Protocol) index for Pkl source files"

  override fun run() {
    try {
      val folders = sourceRoots.filter { it.isDirectory() }.map { it.toRealPath() }
      val files = sourceRoots.filter { it.isRegularFile() }.map { it.toRealPath() }

      val commonRootForFiles = findCommonRoot(files)
      val commonRoot = findCommonRoot(folders + commonRootForFiles)

      val pklFiles = findPklFiles(files + folders)
      // Use the common function to properly set up the project
      val (project, server) = createProjectWithServer(folders + commonRootForFiles, verbose = true)

      val scipAnalyzer = ScipAnalyzer(project, commonRoot)

      var processedCount = 0
      for (pklFile in pklFiles) {
        echo("Processing $pklFile ($processedCount/${pklFiles.size})")
        try {
          val absolutePath = pklFile.toAbsolutePath()
          val virtualFile = project.virtualFileManager.get(absolutePath.toUri())
          if (virtualFile != null) {
            scipAnalyzer.analyzeFile(virtualFile)
          } else {
            echo("Warning: Could not load $absolutePath", err = true)
          }
          processedCount++
        } catch (e: Exception) {
          echo("Error processing $pklFile: ${e.message}", err = true)
          e.printStackTrace()
          processedCount++
        }
      }

      val index = scipAnalyzer.buildIndex()

      outputFile.parent?.createDirectories()
      FileOutputStream(outputFile.toFile()).use { output -> index.writeTo(output) }

      val externalSymbols = index.externalSymbolsCount
      val documentSymbols = index.documentsList.fold(0, { count, doc -> count + doc.symbolsCount })

      echo("SCIP index written to $outputFile")
      echo(
        "Index contains ${index.getDocumentsCount()} documents and ${externalSymbols + documentSymbols} symbols [${documentSymbols} internal; ${externalSymbols} external]"
      )

      server.shutdown().get()
      server.exit()
    } catch (e: Exception) {
      echo("Error generating SCIP index: ${e.message}", err = true)
      e.printStackTrace()
      exitProcess(1)
    }
  }

  companion object {
    data class ProjectWithServer(val project: Project, val server: PklLspServer)

    /**
     * Low-level function that creates an LSP server and project for a specific workspace folder.
     * Most code should use createProjectForFiles() instead for proper file-to-project association.
     */
    fun createProjectWithServer(
      workspaceFolders: List<Path>,
      verbose: Boolean = true,
    ): ProjectWithServer {
      val workspaceFoldersLsp =
        workspaceFolders.map { workspaceFolder ->
          org.eclipse.lsp4j.WorkspaceFolder().apply {
            uri = workspaceFolder.toUri().toString()
            name = workspaceFolder.fileName?.toString() ?: "workspace"
          }
        }

      val server = PklLspServer(verbose)
      val client =
        object : org.pkl.lsp.PklLanguageClient {
          override fun sendActionableNotification(
            params: org.pkl.lsp.messages.ActionableNotification
          ) {
            if (verbose) println("LSP Actionable Notification: ${params}")
          }

          override fun telemetryEvent(`object`: Any?) {
            if (verbose) println("LSP Telemetry: $`object`")
          }

          override fun publishDiagnostics(
            diagnostics: org.eclipse.lsp4j.PublishDiagnosticsParams?
          ) {
            if (verbose)
              println("LSP Diagnostics for ${diagnostics?.uri}: ${diagnostics?.diagnostics}")
          }

          override fun showMessage(messageParams: org.eclipse.lsp4j.MessageParams?) {
            if (verbose) println("LSP Message: [${messageParams?.type}] ${messageParams?.message}")
          }

          override fun showMessageRequest(
            requestParams: org.eclipse.lsp4j.ShowMessageRequestParams?
          ) = java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem>()

          override fun logMessage(message: org.eclipse.lsp4j.MessageParams) {
            if (verbose) println("LSP Log: [${message.type}] ${message.message}")
          }

          override fun workspaceFolders() =
            java.util.concurrent.CompletableFuture.completedFuture(workspaceFoldersLsp)

          override fun configuration(
            configurationParams: org.eclipse.lsp4j.ConfigurationParams
          ): java.util.concurrent.CompletableFuture<MutableList<Any>> {
            if (verbose)
              println(
                "Configuration requested: ${configurationParams.items.map { "${it.scopeUri}/${it.section}" }}"
              )
            // Return JsonNull for pkl.cli.path to trigger automatic CLI discovery
            val result = mutableListOf<Any>()
            configurationParams.items.forEach { _ -> result.add(com.google.gson.JsonNull.INSTANCE) }
            return java.util.concurrent.CompletableFuture.completedFuture(result)
          }
        }

      // Connect client to server
      server.connect(client)

      // Perform proper LSP initialization handshake
      val initParams =
        org.eclipse.lsp4j.InitializeParams().apply {
          capabilities =
            org.eclipse.lsp4j.ClientCapabilities().apply {
              workspace =
                org.eclipse.lsp4j.WorkspaceClientCapabilities().apply {
                  this.workspaceFolders = true
                  didChangeConfiguration =
                    org.eclipse.lsp4j.DidChangeConfigurationCapabilities().apply {
                      dynamicRegistration = false
                    }
                }
              textDocument = org.eclipse.lsp4j.TextDocumentClientCapabilities()
            }
          this.workspaceFolders = workspaceFoldersLsp
        }

      // Initialize server (sets up capabilities and workspace folders)
      server.initialize(initParams).get()
      server.initialized(InitializedParams())

      // Sync projects to make sure dependencies are downloaded and PklProject files are discovered
      // and fully resolved without this we can't get accurate package URIs
      server.syncProjects("").get()

      val project = server.project
      // Initialize project, this will initialize all the project subcomponents and the returned
      // future completes once everything is ready. We don't technically need to do this because
      // server the server initialized method already calls project.initialize, and we don't need
      // the subcomponents that project.initialize sets up. But it's safer to call it again, and
      // makse sure we wait for everything to be ready. The alternative is exposing ourselves too
      // hard to debug race-conditions in the future.
      project.initialize().get()

      return ProjectWithServer(project, server)
    }

    private fun findPklFiles(roots: List<Path>): List<Path> {
      return roots.flatMap { root ->
        when {
          root.isRegularFile() && root.extension == "pkl" -> listOf(root)
          root.isDirectory() -> {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root
              .walk()
              .filter {
                it.isRegularFile() && (it.extension == "pkl" || it.fileName.name == "PklProject")
              }
              .toList()
          }

          else -> emptyList()
        }
      }
    }

    private fun findCommonRoot(paths: List<Path>): Path {
      if (paths.isEmpty()) return Path.of("")
      if (paths.size == 1) return paths[0] ?: Path.of("")

      // Remove empty paths, and make the rest absolute
      val absolutePaths = paths.filter { it.toString() != "" }.map { it.toAbsolutePath() }
      var commonRoot = absolutePaths[0] ?: return Path.of("")

      for (path in absolutePaths.drop(1)) {
        while (!path.startsWith(commonRoot)) {
          commonRoot = commonRoot.parent ?: return Path.of("")
        }
      }

      return commonRoot
    }
  }
}
