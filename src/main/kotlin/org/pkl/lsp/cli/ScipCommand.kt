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
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess
import org.eclipse.lsp4j.*
import org.pkl.lsp.PklLanguageClient
import org.pkl.lsp.PklLspServer
import org.pkl.lsp.Project
import org.pkl.lsp.messages.ActionableNotification
import org.pkl.lsp.scip.ScipAnalyzer
import org.pkl.lsp.scip.ScipAnalysisException
import org.pkl.lsp.util.PathUtils

// Custom exceptions for CLI operations
sealed class ScipCommandException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  
  class FileProcessingException(
    filePath: Path, 
    cause: Throwable
  ) : ScipCommandException("Failed to process file: $filePath", cause)
  
  class IndexWriteException(
    outputPath: Path, 
    cause: Throwable
  ) : ScipCommandException("Failed to write SCIP index to: $outputPath", cause)
  
  class ProjectSetupException(
    message: String, 
    cause: Throwable
  ) : ScipCommandException("Failed to set up LSP project: $message", cause)
}

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
    val indexingService = ScipIndexingService()
    
    when (val result = indexingService.generateIndex(
      sourceRoots = sourceRoots,
      outputFile = outputFile,
      onProgress = ::echo
    )) {
      is IndexingResult.Success -> {
        echo("SCIP index written to ${result.outputFile}")
        echo("Index contains ${result.documentCount} documents and ${result.symbolCount} symbols")
      }
      is IndexingResult.Failure -> {
        echo("Error generating SCIP index: ${result.error.message}", err = true)
        result.error.printStackTrace()
        exitProcess(1)
      }
    }
  }

  // Path resolution with lazy properties
  class PathResolver(private val sourceRoots: List<Path>) {
    val folders by lazy { sourceRoots.filter(Path::isDirectory).map(Path::toRealPath) }
    val files by lazy { sourceRoots.filter(Path::isRegularFile).map(Path::toRealPath) }
    val commonRoot by lazy { PathUtils.findCommonRoot(folders + PathUtils.findCommonRoot(files)) }
    val workspaceFolders by lazy { folders + PathUtils.findCommonRoot(files) }
    val pklFiles by lazy { PathUtils.findPklFiles(files + folders) }
  }

  // Result sealed class for type-safe results
  sealed class IndexingResult {
    data class Success(
      val documentCount: Int,
      val symbolCount: Int,
      val outputFile: Path
    ) : IndexingResult()
    
    data class Failure(val error: Throwable) : IndexingResult()
  }

  // Service class for SCIP indexing logic
  class ScipIndexingService {
    fun generateIndex(
      sourceRoots: List<Path>,
      outputFile: Path,
      onProgress: (String) -> Unit = {}
    ): IndexingResult {
      return try {
        val pathResolver = PathResolver(sourceRoots)
        val (project, server) = createProjectWithServer(pathResolver.workspaceFolders, verbose = true)
        
        try {
          val analyzer = ScipAnalyzer(project, pathResolver.commonRoot)
          
          pathResolver.pklFiles.forEachIndexed { index, pklFile ->
            onProgress("Processing $pklFile (${index + 1}/${pathResolver.pklFiles.size})")
            
            try {
              val absolutePath = pklFile.toAbsolutePath()
              val virtualFile = project.virtualFileManager.get(absolutePath.toUri())
              if (virtualFile != null) {
                analyzer.analyzeFile(virtualFile)
              } else {
                onProgress("Warning: Could not load $absolutePath")
              }
            } catch (e: ScipAnalysisException) {
              onProgress("SCIP analysis error in $pklFile: ${e.message}")
            } catch (e: IOException) {
              onProgress("I/O error processing $pklFile: ${e.message}")
            } catch (e: Exception) {
              onProgress("Unexpected error processing $pklFile: ${e.message}")
              e.printStackTrace()
            }
          }
          
          val index = analyzer.buildIndex()
          writeIndex(index, outputFile)
          
          IndexingResult.Success(
            documentCount = index.documentsCount,
            symbolCount = index.externalSymbolsCount + index.documentsList.sumOf { it.symbolsCount },
            outputFile = outputFile
          )
        } finally {
          server.shutdown().get()
          server.exit()
        }
      } catch (e: ScipCommandException) {
        IndexingResult.Failure(e)
      } catch (e: ScipAnalysisException) {
        IndexingResult.Failure(e)
      } catch (e: IOException) {
        IndexingResult.Failure(ScipCommandException.IndexWriteException(outputFile, e))
      } catch (e: Exception) {
        IndexingResult.Failure(ScipCommandException.ProjectSetupException("Unexpected error", e))
      }
    }
    
    private fun writeIndex(index: scip.Scip.Index, outputFile: Path) {
      try {
        outputFile.parent?.createDirectories()
        outputFile.toFile().outputStream().use { output -> 
          index.writeTo(output) 
        }
      } catch (e: IOException) {
        throw ScipCommandException.IndexWriteException(outputFile, e)
      } catch (e: Exception) {
        throw ScipCommandException.IndexWriteException(outputFile, e)
      }
    }
  }

  // Data class for LSP server configuration
  data class LspServerConfig(
    val workspaceFolders: List<Path>,
    val verbose: Boolean = true
  ) {
    val workspaceFoldersLsp: List<WorkspaceFolder> by lazy {
      workspaceFolders.map { folder ->
        WorkspaceFolder().apply {
          uri = folder.toUri().toString()
          name = folder.fileName?.toString() ?: "workspace"
        }
      }
    }
  }

  companion object {
    data class ProjectWithServer(val project: Project, val server: PklLspServer)

    // Extension function for server configuration
    private fun PklLspServer.configureWith(client: PklLanguageClient, config: LspServerConfig) = apply {
      connect(client)
      
      val initParams = InitializeParams().apply {
        capabilities = ClientCapabilities().apply {
          workspace = WorkspaceClientCapabilities().apply {
            workspaceFolders = true
            didChangeConfiguration = DidChangeConfigurationCapabilities().apply {
              dynamicRegistration = false
            }
          }
          textDocument = TextDocumentClientCapabilities()
        }
        workspaceFolders = config.workspaceFoldersLsp
      }
      
      initialize(initParams).get()
      initialized(InitializedParams())
      syncProjects("").get()
    }

    // Extension for project initialization
    private fun Project.ensureInitialized() = apply { initialize().get() }

    // Extract client creation
    private fun createLspClient(config: LspServerConfig) = object : PklLanguageClient {
      private fun log(message: String) {
        if (config.verbose) println(message)
      }
      
      override fun sendActionableNotification(params: ActionableNotification) = 
        log("LSP Actionable Notification: $params")
      
      override fun telemetryEvent(`object`: Any?) = 
        log("LSP Telemetry: $`object`")
      
      override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) = 
        log("LSP Diagnostics for ${diagnostics?.uri}: ${diagnostics?.diagnostics}")
      
      override fun showMessage(messageParams: MessageParams?) = 
        log("LSP Message: [${messageParams?.type}] ${messageParams?.message}")
      
      override fun showMessageRequest(requestParams: ShowMessageRequestParams?) = 
        java.util.concurrent.CompletableFuture<MessageActionItem>()
      
      override fun logMessage(message: MessageParams) = 
        log("LSP Log: [${message.type}] ${message.message}")
      
      override fun workspaceFolders() = 
        java.util.concurrent.CompletableFuture.completedFuture(config.workspaceFoldersLsp)
      
      override fun configuration(configurationParams: ConfigurationParams): java.util.concurrent.CompletableFuture<MutableList<Any?>> = 
        java.util.concurrent.CompletableFuture.completedFuture(
          configurationParams.items.map { com.google.gson.JsonNull.INSTANCE as Any? }.toMutableList()
        ).also {
          if (config.verbose) {
            val sections = configurationParams.items.joinToString { "${it.scopeUri}/${it.section}" }
            log("Configuration requested: $sections")
          }
        }
    }

    /**
     * Creates an LSP server and project for specific workspace folders.
     */
    fun createProjectWithServer(
      workspaceFolders: List<Path>,
      verbose: Boolean = true
    ): ProjectWithServer {
      val config = LspServerConfig(workspaceFolders, verbose)
      val client = createLspClient(config)
      val server = PklLspServer(verbose).configureWith(client, config)
      val project = server.project.ensureInitialized()
      
      return ProjectWithServer(project, server)
    }

  }
}
