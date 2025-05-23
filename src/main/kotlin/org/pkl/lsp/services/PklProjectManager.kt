/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import org.eclipse.lsp4j.*
import org.pkl.lsp.*
import org.pkl.lsp.FsFile
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.packages.dto.PklProject.Companion.DerivedProjectMetadata
import org.pkl.lsp.packages.dto.RemoteDependency
import org.pkl.lsp.util.SimpleModificationTracker

val projectTopic = Topic<ProjectEvent>("ProjectEvent")

sealed interface ProjectEvent {
  data object ProjectsSynced : ProjectEvent
}

class PklProjectManager(project: Project) : Component(project) {
  companion object {
    const val PKL_PROJECT_FILENAME = "PklProject"
    const val PKL_PROJECT_DEPS_FILENAME = "PklProject.deps.json"
    const val PKL_PROJECT_STATE_FILENAME = "projects.json"
    const val PKL_MODULE_PATH_FILENAME = ".pkl-module-path"
    const val PKL_LSP_DIR = ".pkl-lsp"

    private const val DEPENDENCIES_EXPR =
      """
      new JsonRenderer { omitNullProperties = false }
        .renderValue(new Dynamic {
          projectFileUri = module.projectFileUri
          packageUri = module.package?.uri
          declaredDependencies = module.dependencies.toMap().mapValues((_, value) ->
            if (value is RemoteDependency) value.uri
            else value.package.uri
          )
          evaluatorSettings = module.evaluatorSettings
        })
      """
  }

  /**
   * Returns the PklProject associated with the provided [file].
   *
   * [file] is either a directory, or a PklProject file.
   */
  fun getPklProject(file: VirtualFile): PklProject? =
    if (file !is FsFile) null else pklProjects[file.projectKey]

  /** Tracks when PklProject files get added or deleted from the workspace. */
  val addedOrRemovedFilesModificationTracker =
    project.pklFileTracker.filter { event -> event.files.any { it.path.endsWith("PklProject") } }

  /** Tracks when projects get sync'd. */
  val syncTracker = SimpleModificationTracker()

  fun initialize(folders: List<Path>?) {
    project.messageBus.subscribe(textDocumentTopic, ::handleTextDocumentEvent)
    project.messageBus.subscribe(workspaceFolderTopic, ::handleWorkspaceFolderEvent)
    workspaceFolders.clear()
    pklProjects.clear()
    lastPklProjectSyncState.clear()
    if (folders != null) {
      workspaceFolders.addAll(folders)
      loadState()
    }
  }

  fun syncProjects(emitEvents: Boolean = false): CompletableFuture<Unit> {
    val pklProjectFiles = discoverProjectFiles()
    if (pklProjectFiles.isEmpty()) {
      pklProjects.clear()
      lastPklProjectSyncState.clear()
      return CompletableFuture.completedFuture(Unit)
    }
    return project.pklCli
      .resolveProject(pklProjectFiles.map { it.path.parent })
      .thenCompose { getProjectMetadatas(pklProjectFiles) }
      .thenApply { metadatas ->
        pklProjects.clear()
        lastPklProjectSyncState.clear()
        for (metadata in metadatas) {
          val projectFile = project.virtualFileManager.getFsFile(metadata.projectFileUri)!!
          val key = projectFile.projectKey
          val projectDir = projectFile.parent()!!
          val resolvedDepsPath = projectDir.resolve(PKL_PROJECT_DEPS_FILENAME)
          val resolvedDeps = resolvedDepsPath?.let { PklProject.loadProjectDeps(it) }
          val pklProject = PklProject(metadata, resolvedDeps)
          pklProjects[key] = pklProject
          lastPklProjectSyncState[key] = projectFile.getModificationCount()
          doDownloadDependencies(pklProject, pklCacheDir)
        }
        persistState()
        project.languageClient.showMessage(
          MessageParams(MessageType.Info, "Project sync successful")
        )
        syncTracker.increment()
        if (emitEvents) {
          project.messageBus.emit(projectTopic, ProjectEvent.ProjectsSynced)
        }
      }
      .exceptionally { err ->
        project.languageClient.showMessage(
          MessageParams(
            MessageType.Error,
            """
        Failed to sync project.

        ${err.cause!!.stackTraceToString()}
      """
              .trimIndent(),
          )
        )
      }
  }

  /** All workspace folders managed by the client. */
  private val workspaceFolders: MutableSet<Path> = mutableSetOf()

  /** All projects, keyed by the project directory. */
  private val pklProjects: MutableMap<URI, PklProject> = ConcurrentHashMap()

  /**
   * All modulepath entries from a local ".pkl-module-path" that can be resolved to a existing path.
   */
  fun findModulePath(): List<Path> {
    return workspaceFolders
      .map { it.resolve(PKL_MODULE_PATH_FILENAME) }
      .first(Files::exists)
      ?.toFile()
      ?.bufferedReader()
      ?.readLines()
      ?.filter { !it.isBlank() && !it.trimStart().startsWith('#') }
      ?.mapNotNull {
        try {
          val path = Path.of(it)
          if (Files.exists(path)) {
            return@mapNotNull path
          }
          // logger.log("modulepath entry does not exist: $it")
        } catch (_: InvalidPathException) {
          // logger.log("bad modulepath entry: $it")
        }
        null
      } ?: listOf()
  }

  /**
   * The modification count of each project file during the last sync, keyed by the project
   * directory.
   */
  private val lastPklProjectSyncState: MutableMap<URI, Long> = ConcurrentHashMap()

  private val FsFile.projectKey
    get(): URI =
      if (name == PKL_PROJECT_FILENAME) this.path.toUri()
      else this.path.resolve(PKL_PROJECT_FILENAME).toUri()

  private fun isPklProjectFileClean(file: FsFile): Boolean =
    file.getModificationCount() == lastPklProjectSyncState[file.projectKey]

  private fun handleTextDocumentEvent(event: TextDocumentEvent) {
    val file = project.virtualFileManager.getFsFile(event.file) ?: return
    if (
      file.path.endsWith("PklProject") &&
        event is TextDocumentEvent.Saved &&
        file.pklProject != null &&
        !isPklProjectFileClean(file) &&
        project.settingsManager.settings.pklCliPath != null
    ) {
      project.languageClient
        .showMessageRequest(
          ShowMessageRequestParams().apply {
            this.type = MessageType.Info
            this.message = ErrorMessages.create("pklProjectFileModified")
            this.actions = listOf(MessageActionItem().apply { this.title = "Sync Projects" })
          }
        )
        .thenApply { response ->
          if (response?.title == "Sync Projects") {
            project.pklProjectManager.syncProjects(true)
          }
        }
    }
    if (
      event is TextDocumentEvent.Opened &&
        file.isInWorkspace &&
        file.pklProjectDir != null &&
        file.pklProject == null &&
        project.settingsManager.settings.pklCliPath != null
    ) {
      project.languageClient
        .showMessageRequest(
          ShowMessageRequestParams().apply {
            this.type = MessageType.Info
            this.message = ErrorMessages.create("unsyncedPklProject")
            this.actions = listOf(MessageActionItem().apply { this.title = "Sync Projects" })
          }
        )
        .thenApply { response ->
          if (response?.title == "Sync Projects") {
            project.pklProjectManager.syncProjects(true)
          }
        }
    }
  }

  private fun handleWorkspaceFolderEvent(event: WorkspaceFoldersChangeEvent) =
    synchronized(this) {
      for (workspaceFolder in event.added) {
        val path = Path.of(URI(workspaceFolder.uri))
        workspaceFolders.add(path)
        loadWorkspaceState(path)
      }
      for (workspaceFolder in event.removed) {
        val path = Path.of(URI(workspaceFolder.uri))
        workspaceFolders.removeIf { it.toUri() == path.toUri() }
        unloadWorkspaceState(path)
      }
      project.messageBus.emit(projectTopic, ProjectEvent.ProjectsSynced)
    }

  private fun doDownloadDependencies(pklProject: PklProject, cacheDir: Path) {
    val packageUris =
      pklProject.projectDeps
        ?.resolvedDependencies
        ?.values
        ?.filterIsInstance<RemoteDependency>()
        ?.map { it.uri.copy(checksums = it.checksums) }
        ?.ifEmpty { null } ?: return
    project.pklCli
      .downloadPackage(
        packageUris,
        cacheDir = cacheDir,
        // All transitive dependencies are already declared in PklProject.deps.json
        noTransitive = true,
      )
      .get()
  }

  private fun getProjectMetadatas(
    projectFiles: List<FsFile>
  ): CompletableFuture<List<DerivedProjectMetadata>> {
    return project.pklCli
      .eval(
        projectFiles.map { it.path.absolutePathString() },
        expression = DEPENDENCIES_EXPR,
        moduleOutputSeparator = ", ",
      )
      .thenApply { PklProject.parseMetadata("[$it]") }
  }

  private fun discoverProjectFiles(): List<FsFile> {
    return workspaceFolders.flatMap { folderRoot ->
      buildList {
        Files.walkFileTree(
          folderRoot,
          object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
              if (file.name == PKL_PROJECT_FILENAME) {
                add(project.virtualFileManager.getFsFile(file)!!)
              }
              return FileVisitResult.CONTINUE
            }
          },
        )
      }
    }
  }

  private fun persistState() {
    val projectsByWorkspaceDir =
      pklProjects.values.groupBy { pklProject ->
        workspaceFolders.find { pklProject.projectDir.startsWith(it) }!!
      }
    for ((workspace, pklProjects) in projectsByWorkspaceDir) {
      val state =
        PklWorkspaceState(
          schemaVersion = 1,
          pklProjects = pklProjects.map { it.toState(workspace) },
        )
      val lspDir = workspace.resolve(PKL_LSP_DIR)
      Files.createDirectories(lspDir)
      state.dump(lspDir.resolve(PKL_PROJECT_STATE_FILENAME))
    }
  }

  private fun loadWorkspaceState(workspace: Path) {
    val stateFile = workspace.resolve(PKL_LSP_DIR).resolve(PKL_PROJECT_STATE_FILENAME)
    if (!Files.exists(stateFile)) {
      return
    }
    val state: PklWorkspaceState = PklWorkspaceState.load(stateFile)
    logger.log("Decoded state: $state")
    for (pklProjectState in state.pklProjects) {
      val pklProject = loadProjectFromState(pklProjectState, workspace)
      val projectFile = project.virtualFileManager.getFsFile(pklProject.metadata.projectFileUri)!!
      pklProjects[projectFile.projectKey] = pklProject
      lastPklProjectSyncState[projectFile.projectKey] = projectFile.getModificationCount()
    }
  }

  private fun unloadWorkspaceState(workspace: Path) {
    val myProjects = pklProjects.values.filter { it.projectDir.startsWith(workspace) }
    for (pklProject in myProjects) {
      val projectFile = project.virtualFileManager.getFsFile(pklProject.metadata.projectFileUri)!!
      pklProjects.remove(projectFile.projectKey)
      lastPklProjectSyncState.remove(projectFile.projectKey)
    }
  }

  private fun loadState() {
    pklProjects.clear()
    lastPklProjectSyncState.clear()
    for (workspace in workspaceFolders) {
      try {
        loadWorkspaceState(workspace)
      } catch (e: Throwable) {
        logger.warn("Failed to load state from $workspace")
        logger.warn(e.stackTraceToString())
      }
    }
    project.messageBus.emit(projectTopic, ProjectEvent.ProjectsSynced)
  }

  private fun loadProjectFromState(pklProjectState: PklProjectState, workspace: Path): PklProject {
    val metadata =
      DerivedProjectMetadata(
        workspace.resolve(pklProjectState.projectFile).toUri(),
        pklProjectState.packageUri,
        pklProjectState.declaredDependencies,
        pklProjectState.evaluatorSettings,
      )
    val projectDeps =
      if (pklProjectState.resolvedDependencies?.isNotEmpty() == true)
        PklProject.Companion.ProjectDeps(1, pklProjectState.resolvedDependencies)
      else null
    return PklProject(metadata, projectDeps)
  }

  private fun PklProject.toState(workspace: Path): PklProjectState {
    return PklProjectState(
      projectFile = workspace.relativize(projectFile).normalize().toUnixPathString(),
      packageUri = metadata.packageUri,
      declaredDependencies = metadata.declaredDependencies,
      resolvedDependencies = projectDeps?.resolvedDependencies,
      evaluatorSettings = metadata.evaluatorSettings,
    )
  }

  private val FsFile.isInWorkspace: Boolean
    get() = workspaceFolders.any { path.startsWith(it) }
}
