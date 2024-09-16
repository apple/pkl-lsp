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
package org.pkl.lsp.services

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
      ModuleMemberAnalyzer(project),
      ModuleUriAnalyzer(project),
      SyntaxAnalyzer(project),
    )

  private val openFiles: MutableMap<URI, Boolean> = ConcurrentHashMap()
  private val downloadPackageTracker = SimpleModificationTracker()

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
      }
      is TextDocumentEvent.Changed -> {
        doPublishDiagnostics(event.file)
      }
      is TextDocumentEvent.Closed -> {
        openFiles.remove(event.file)
      }
      else -> null /* no-op */
    }
  }

  private fun handleProjectEvent(event: ProjectEvent) {
    if (event is ProjectEvent.ProjectsSynced) return
    for (file in openFiles.keys) {
      doPublishDiagnostics(file)
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
      val diagnostics = getDiagnostics(module)
      project.languageClient.publishDiagnostics(
        PublishDiagnosticsParams(uri.toString(), diagnostics.map { it.toMessage() })
      )
    }
  }

  fun getDiagnostics(module: PklModule): List<PklDiagnostic> {
    return project.cachedValuesManager.getCachedValue(
      "DiagnosticsManager.getDiagnostics(${module.uri})"
    ) {
      val diagnostics = mutableListOf<PklDiagnostic>()
      for (analyzer in analyzers) {
        analyzer.analyze(module, diagnostics)
      }
      logger.log("Found ${diagnostics.size} diagnostic errors for ${module.uri}")
      val dependencies = buildList {
        val file = module.containingFile

        // invalidate if this module, or any of its supermodules have changed
        addAll(module.cache(file.pklProject).dependencies)

        // invalidate diagnostics if a package gets downloaded
        add(downloadPackageTracker)

        // invalidate diagnostics when a project sync happens if this a local filesystem file
        if (file is FsFile) {
          add(project.pklProjectManager.syncTracker)
        }
      }
      CachedValue(diagnostics, dependencies)
    }!!
  }
}
