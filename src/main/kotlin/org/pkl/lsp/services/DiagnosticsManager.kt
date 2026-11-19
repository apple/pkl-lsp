/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.services

import com.google.errorprone.annotations.concurrent.GuardedBy
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.pkl.lsp.*
import org.pkl.lsp.analyzers.*
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.util.CachedValue
import org.pkl.lsp.util.SimpleModificationTracker

/** Manages diagnostics that get fired back to the client. */
class DiagnosticsManager(project: Project) : Component(project) {
  private val analyzers: List<Analyzer> =
    listOf(
      AnnotationAnalyzer(project),
      ModifierAnalyzer(project),
      MemberAnalyzer(project),
      ModuleUriAnalyzer(project),
      SyntaxAnalyzer(project),
      UnsupportedFeatureAnalyzer(project),
      TypeCheckAnalyzer(project),
      AccessExprAnalyzer(project),
      ExprAnalyzer(project),
      TypeNameAnalyzer(project),
      TypeAnalyzer(project),
      UnusedLocalDefinitionAnalyzer(project),
      ExtendsClauseAnalyzer(project),
      DocCommentAnalyzer(project),
    )

  private val externalReaderAnalyzer = ExternalReaderDeclarationAnalyzer(project)

  /**
   * Diagnostics for `PklProject` files that are slow to compute (they need to talk to external
   * reader processes), so they're computed out-of-band: when a file is opened, and when projects
   * are synced.
   *
   * `publishDiagnostics` replaces everything that's published for a file, so these are merged into
   * what [doPublishDiagnostics] publishes.
   */
  private val externalReaderDiagnostics: MutableMap<URI, List<PklDiagnostic>> = ConcurrentHashMap()

  /** The latest external reader analysis per file; results of superseded analyses are dropped. */
  private val externalReaderAnalyses: MutableMap<URI, CompletableFuture<*>> = ConcurrentHashMap()

  private val openFiles: MutableMap<URI, Boolean> = ConcurrentHashMap()
  private val downloadPackageTracker = SimpleModificationTracker()

  private val lock = Any()

  override fun initialize(): CompletableFuture<*> {
    project.messageBus.subscribe(textDocumentTopic, ::handleTextDocumentEvent)
    project.messageBus.subscribe(projectTopic, ::handleProjectEvent)
    project.messageBus.subscribe(packageTopic, ::handlePackageEvent)
    return CompletableFuture.completedFuture(Unit)
  }

  private fun handleTextDocumentEvent(event: TextDocumentEvent) {
    @Suppress("UNUSED_EXPRESSION")
    when (event) {
      is TextDocumentEvent.Opened -> {
        doPublishDiagnostics(event.file)
        openFiles[event.file] = true
        analyzeExternalReaders(event.file)
      }
      is TextDocumentEvent.Changed -> {
        // what was found about the readers is stale (positions may have moved); it's recomputed
        // when the file is opened again, or projects are synced.
        clearExternalReaderDiagnostics(event.file)
        for (file in openFiles.keys) {
          doPublishDiagnostics(file)
        }
      }
      is TextDocumentEvent.Closed -> {
        openFiles.remove(event.file)
        clearExternalReaderDiagnostics(event.file)
      }
      else -> null /* no-op */
    }
  }

  private fun handleProjectEvent(event: ProjectEvent) {
    when (event) {
      is ProjectEvent.ProjectsSynced -> {
        for (file in openFiles.keys) {
          analyzeExternalReaders(file)
        }
      }
      // Imports that go through external readers may resolve differently now. (The readers
      // declared by `PklProject` files are checked when projects are synced.)
      is ProjectEvent.ExternalReadersChanged -> {
        for (file in openFiles.keys) {
          doPublishDiagnostics(file)
        }
      }
    }
  }

  /**
   * The diagnostics of external readers declared by [uri] that were found so far.
   *
   * These aren't part of [getDiagnostics] because finding them is slow.
   */
  fun getExternalReaderDiagnostics(uri: URI): List<PklDiagnostic> =
    externalReaderDiagnostics[uri].orEmpty()

  private fun clearExternalReaderDiagnostics(uri: URI) {
    externalReaderAnalyses.remove(uri)
    externalReaderDiagnostics.remove(uri)
  }

  /**
   * If [uri] is a synced `PklProject` file, checks the external readers that it declares in the
   * background, and publishes the result once it's known.
   */
  private fun analyzeExternalReaders(uri: URI) {
    val file = project.virtualFileManager.get(uri) as? FsFile ?: return
    if (file.name != PklProjectManager.PKL_PROJECT_FILENAME) return
    file.getModule().thenApply { module ->
      // only projects that were synced know about the readers they declare
      val context = file.pklProject
      if (module == null || context == null) {
        clearExternalReaderDiagnostics(uri)
        return@thenApply
      }
      val analysis = CompletableFuture<Unit>()
      externalReaderAnalyses[uri] = analysis
      try {
        val diagnostics = externalReaderAnalyzer.analyze(module, context)
        // superseded by a newer analysis, or the file changed or was closed in the meantime
        if (externalReaderAnalyses[uri] !== analysis) return@thenApply
        externalReaderDiagnostics[uri] = diagnostics
        doPublishDiagnostics(uri)
      } catch (e: Exception) {
        logger.warn("Failed to analyze external readers of $uri: ${e.stackTraceToString()}")
      } finally {
        analysis.complete(Unit)
      }
    }
  }

  private fun handlePackageEvent(event: PackageEvent) {
    if (event !is PackageEvent.PackageDownloaded) return
    downloadPackageTracker.increment()
    for (file in openFiles.keys) {
      doPublishDiagnostics(file)
    }
  }

  private fun doPublishDiagnostics(uri: URI) {
    val file = project.virtualFileManager.get(uri) ?: return
    file.getModule().thenApply { module ->
      if (module == null) return@thenApply
      val diagnostics = getDiagnostics(module) + getExternalReaderDiagnostics(uri)
      project.languageClient.publishDiagnostics(
        PublishDiagnosticsParams(uri.toString(), diagnostics.map { it.toMessage() })
      )
    }
  }

  @GuardedBy("lock")
  fun getDiagnostics(module: PklModule): List<PklDiagnostic> {
    return project.cachedValuesManager.getCachedValue(
      "DiagnosticsManager.getDiagnostics(${module.uri})",
      lock,
    ) {
      val holder = DiagnosticsHolder()
      for (analyzer in analyzers) {
        analyzer.analyze(module, holder)
      }
      logger.log("Found ${holder.diagnostics.size} diagnostic errors for ${module.uri}")
      val dependencies = buildList {
        val file = module.containingFile

        // invalidate if this module, or any of its supermodules have changed
        addAll(module.cache(file.pklProject).dependencies)

        // invalidate diagnostics if a package gets downloaded
        add(downloadPackageTracker)

        // imports may resolve through external readers
        add(project.externalReaderService.modificationTracker)

        // invalidate diagnostics when a project sync happens if this a local filesystem file
        if (file is FsFile) {
          add(project.pklProjectManager.syncTracker)
        }
      }
      CachedValue(holder.diagnostics, dependencies)
    }!!
  }
}
