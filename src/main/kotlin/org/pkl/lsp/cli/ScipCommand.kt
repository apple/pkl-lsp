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
import org.pkl.lsp.Project
import org.pkl.lsp.PklLspServer
import org.pkl.lsp.VirtualFileManager
import org.pkl.lsp.scip.ScipAnalyzer

class ScipCommand : CliktCommand(name = "scip") {
  
  private val outputFile: Path by option(
    "--output", "-o",
    help = "Output file for SCIP index (default: index.scip)"
  ).path().default(Path.of("index.scip"))
  
  private val sourceRoots: List<Path> by argument(
    help = "Source directories or files to index"
  ).path(mustExist = true).multiple(required = true)
  
  override fun help(context: Context): String =
    "Generate SCIP (Source Code Intelligence Protocol) index for Pkl source files"

  override fun run() {
    try {
      val allPklFiles = findPklFiles(sourceRoots)
      if (allPklFiles.isEmpty()) {
        echo("No .pkl files found in the specified paths", err = true)
        exitProcess(1)
      }
      
      echo("Found ${allPklFiles.size} .pkl files to index")
      
      // Use the common function to properly set up the project
      val projectWithServer = createProjectForFiles(allPklFiles, verbose = false)
      val project = projectWithServer.project
      val server = projectWithServer.server
      
      val rootPath = findCommonRoot(allPklFiles.map { it.toAbsolutePath() })
      val scipAnalyzer = ScipAnalyzer(project, rootPath)
      
      for (pklFile in allPklFiles) {
        echo("Processing $pklFile")
        try {
          val absolutePath = pklFile.toAbsolutePath()
          val virtualFile = project.virtualFileManager.get(absolutePath.toUri())
          if (virtualFile != null) {
            scipAnalyzer.analyzeFile(virtualFile)
          } else {
            echo("Warning: Could not load $absolutePath", err = true)
          }
        } catch (e: Exception) {
          echo("Error processing $pklFile: ${e.message}", err = true)
        }
      }
      
      val index = scipAnalyzer.buildIndex()
      
      outputFile.parent?.createDirectories()
      FileOutputStream(outputFile.toFile()).use { output ->
        index.writeTo(output)
      }
      
      echo("SCIP index written to $outputFile")
      echo("Index contains ${index.getDocumentsCount()} documents and ${index.getExternalSymbolsCount()} symbols")
      
      // Don't call dispose() to avoid triggering component initialization during cleanup
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
    private fun createProjectWithServer(workspaceFolder: Path, verbose: Boolean = true): ProjectWithServer {
      val server = PklLspServer(verbose)
      val client = object : org.pkl.lsp.PklLanguageClient {
        override fun sendActionableNotification(params: org.pkl.lsp.messages.ActionableNotification) {
          if (verbose) println("LSP Actionable Notification: ${params}")
        }
        override fun telemetryEvent(`object`: Any?) {
          if (verbose) println("LSP Telemetry: $`object`")
        }
        override fun publishDiagnostics(diagnostics: org.eclipse.lsp4j.PublishDiagnosticsParams?) {
          if (verbose) println("LSP Diagnostics for ${diagnostics?.uri}: ${diagnostics?.diagnostics}")
        }
        override fun showMessage(messageParams: org.eclipse.lsp4j.MessageParams?) {
          if (verbose) println("LSP Message: [${messageParams?.type}] ${messageParams?.message}")
        }
        override fun showMessageRequest(requestParams: org.eclipse.lsp4j.ShowMessageRequestParams?) = 
          java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem>()
        override fun logMessage(message: org.eclipse.lsp4j.MessageParams) {
          if (verbose) println("LSP Log: [${message.type}] ${message.message}")
        }
        override fun workspaceFolders() = 
          java.util.concurrent.CompletableFuture.completedFuture(emptyList<org.eclipse.lsp4j.WorkspaceFolder>())
        override fun configuration(configurationParams: org.eclipse.lsp4j.ConfigurationParams): java.util.concurrent.CompletableFuture<MutableList<Any>> {
          if (verbose) println("Configuration requested: ${configurationParams.items.map { "${it.scopeUri}/${it.section}" }}")
          // Return JsonNull for pkl.cli.path to trigger automatic CLI discovery
          val result = mutableListOf<Any>()
          configurationParams.items.forEach { _ -> result.add(com.google.gson.JsonNull.INSTANCE) }
          return java.util.concurrent.CompletableFuture.completedFuture(result)
        }
      }
      
      // Connect client to server
      server.connect(client)
      
      // Perform proper LSP initialization handshake
      val workspaceFolderLsp = org.eclipse.lsp4j.WorkspaceFolder().apply {
        uri = workspaceFolder.toUri().toString()
        name = workspaceFolder.fileName?.toString() ?: "workspace"
      }
      
      val initParams = org.eclipse.lsp4j.InitializeParams().apply {
        capabilities = org.eclipse.lsp4j.ClientCapabilities().apply {
          workspace = org.eclipse.lsp4j.WorkspaceClientCapabilities().apply {
            workspaceFolders = true
            didChangeConfiguration = org.eclipse.lsp4j.DidChangeConfigurationCapabilities().apply {
              dynamicRegistration = false
            }
          }
          textDocument = org.eclipse.lsp4j.TextDocumentClientCapabilities()
        }
        workspaceFolders = listOf(workspaceFolderLsp)
        rootUri = workspaceFolder.toUri().toString()
      }
      
      // Initialize server (sets up capabilities and workspace folders)
      server.initialize(initParams).get()
      
      // Signal initialization complete (this triggers project manager initialization)
      server.initialized(org.eclipse.lsp4j.InitializedParams())
      if (verbose) println("LSP Server initialization completed")
      
      val project = server.project
      
      // Wait for async initialization to complete
      // Check project manager settings initialization
      val settingsManager = project.settingsManager
      try {
        val initFuture = settingsManager.javaClass.getDeclaredField("initialized").apply { isAccessible = true }.get(settingsManager) as java.util.concurrent.CompletableFuture<*>
        initFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
        if (verbose) println("SettingsManager initialization completed")
      } catch (e: Exception) {
        if (verbose) println("Warning: Could not wait for SettingsManager: ${e.message}")
      }
      
      // Explicitly sync projects and wait for completion
      try {
        if (verbose) println("Manually triggering project sync...")
        val syncFuture = project.pklProjectManager.syncProjects()
        syncFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)
        if (verbose) println("Project sync completed successfully")
      } catch (e: Exception) {
        if (verbose) println("Warning: Project sync failed or timed out: ${e.message}")
        if (verbose) e.printStackTrace()
      }
      
      // Wait a bit more for project discovery to complete
      Thread.sleep(1000)
      
      return ProjectWithServer(project, server)
    }
    
    
    /**
     * THE SINGLE FUNCTION FOR PROJECT SETUP.
     * 
     * Creates a Project properly configured for analyzing specific files.
     * Both debug and SCIP commands must use this function to ensure consistent behavior.
     * 
     * Key features:
     * - Smart workspace detection based on PklProject files
     * - Full LSP server initialization with proper handshake
     * - Critical didOpen notifications for all target files
     * - File-to-project association validation
     * 
     * @param targetFiles The files that will be analyzed (used to determine proper workspace folders)
     * @param verbose Whether to print debug information
     * @return A properly configured project with document associations
     */
    fun createProjectForFiles(targetFiles: List<Path>, verbose: Boolean = true): ProjectWithServer {
      // Group files by their potential project directories
      val projectGroups = targetFiles.groupBy { file ->
        findProjectDirectory(file.toAbsolutePath())
      }
      
      // For simplicity, use the most common project directory as workspace
      // TODO: In the future, we could support multiple projects simultaneously
      val mainWorkspace = projectGroups.keys.maxByOrNull { projectGroups[it]?.size ?: 0 } 
        ?: targetFiles.firstOrNull()?.parent?.toAbsolutePath() 
        ?: Path.of(".").toAbsolutePath()
      
      if (verbose && projectGroups.size > 1) {
        println("Warning: Multiple project directories detected. Using $mainWorkspace as main workspace.")
        projectGroups.forEach { (dir, files) ->
          println("  $dir: ${files.size} files")
        }
      }
      
      // Create project with full LSP setup
      val projectWithServer = createProjectWithServer(mainWorkspace, verbose)
      val project = projectWithServer.project
      val server = projectWithServer.server
      
      // Critical: Open all target documents to trigger proper project association
      if (verbose) println("Opening ${targetFiles.size} documents for proper project association...")
      for (file in targetFiles) {
        try {
          val textDocumentItem = org.eclipse.lsp4j.TextDocumentItem().apply {
            uri = file.toUri().toString()
            languageId = "pkl"
            version = 1
            text = file.readText()
          }
          
          val openParams = org.eclipse.lsp4j.DidOpenTextDocumentParams().apply {
            textDocument = textDocumentItem
          }
          
          // This triggers project discovery and file-to-project association
          server.textDocumentService.didOpen(openParams)
          
          if (verbose) {
            // Verify the file is properly associated with its project
            try {
              val fsFile = project.virtualFileManager.getFsFile(file.toUri())
              val pklProject = fsFile?.let { project.pklProjectManager.getPklProject(it) }
              if (pklProject != null) {
                println("  ✓ ${file.fileName}: Associated with ${pklProject.metadata.packageUri}")
              } else {
                println("  ⚠ ${file.fileName}: No PklProject association found")
              }
            } catch (e: Exception) {
              println("  ✗ ${file.fileName}: Error checking project association: ${e.message}")
            }
          }
        } catch (e: Exception) {
          if (verbose) println("  ✗ ${file.fileName}: Error opening document: ${e.message}")
        }
      }
      
      return projectWithServer
    }
    
    /**
     * Find the directory that should be used as the project workspace for a given file.
     * Looks for PklProject files in the file's directory hierarchy.
     */
    private fun findProjectDirectory(filePath: Path): Path {
      var current = filePath.parent
      while (current != null) {
        if (current.resolve("PklProject").exists()) {
          return current
        }
        current = current.parent
      }
      // Fallback to file's parent if no PklProject found
      return filePath.parent ?: filePath
    }
  }
  
  private fun findPklFiles(roots: List<Path>): List<Path> {
    return roots.flatMap { root ->
      when {
        root.isRegularFile() && root.extension == "pkl" -> listOf(root)
        root.isDirectory() -> {
          @OptIn(kotlin.io.path.ExperimentalPathApi::class)
          root.walk()
            .filter { it.isRegularFile() && (it.extension == "pkl" || it.fileName.name == "PklProject") }
            .toList()
        }
        else -> emptyList()
      }
    }
  }
  
  private fun findCommonRoot(paths: List<Path>): Path {
    if (paths.isEmpty()) return Path.of(".")
    if (paths.size == 1) return paths[0].parent ?: Path.of(".")
    
    val absolutePaths = paths.map { it.toAbsolutePath() }
    var commonRoot = absolutePaths[0].parent ?: return Path.of(".")
    
    for (path in absolutePaths.drop(1)) {
      while (!path.startsWith(commonRoot)) {
        commonRoot = commonRoot.parent ?: return Path.of(".")
      }
    }
    
    return commonRoot
  }
}