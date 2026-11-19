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
package org.pkl.lsp.services

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.messages.ActionableNotification
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.services.ExternalReader as WorkspaceReader
import org.pkl.lsp.services.externalreader.ReaderCheck
import org.pkl.lsp.services.externalreader.ReaderKind
import org.pkl.lsp.services.externalreader.ReaderState
import org.pkl.lsp.util.SimpleModificationTracker
import org.pkl.messaging.externalreader.ExternalReader
import org.pkl.messaging.externalreader.ExternalReaderProcess
import org.pkl.messaging.externalreader.ExternalReaderProcessException
import org.pkl.messaging.externalreader.ModuleReaderSpec

/**
 * Resolves modules from external readers, keeping what they return in memory.
 *
 * External readers are declared by `PklProject` files, or by the `pkl.externalModuleReaders`
 * workspace setting (project-level readers take precedence). Two projects may declare the same
 * scheme but use different executables, so everything that depends on the reader is tracked per
 * reader (see [ReaderState]). The reader process is shared between all schemes/projects that have
 * an equal [ExternalReader].
 */
class PklExternalReaderService(project: Project) : Component(project) {
  private companion object {
    const val CHECK_TIMEOUT_SECONDS = 10L
    const val SETTING_NAME = "pkl.externalModuleReaders"
  }

  private val checkExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "pkl-external-reader-check").apply { isDaemon = true }
  }

  private data class ReaderKey(val scheme: String, val reader: ExternalReader)

  /** Reader states, by reader configuration and scheme. */
  private val states: MutableMap<ReaderKey, ReaderState> = ConcurrentHashMap()

  /**
   * Reader processes, keyed by reader configuration.
   *
   * The same configuration (across schemes and projects) shares the same process.
   */
  private val processes: MutableMap<ExternalReader, ExternalReaderProcess> = ConcurrentHashMap()

  override fun initialize(): CompletableFuture<*> {
    // Project settings (and therefore readers) may have changed; what was learned from the old
    // readers is stale.
    project.messageBus.subscribe(projectTopic) { event ->
      if (event is ProjectEvent.ProjectsSynced) invalidate()
    }
    return super.initialize()
  }

  /** Tells if there's an external reader for [scheme], as seen from a module in [context]. */
  fun handlesScheme(scheme: String, context: PklProject?): Boolean =
    configuredReader(scheme, context) != null

  /**
   * Returns the file for [uri], or `null` if there's no reader for its scheme, or the reader
   * doesn't have it.
   *
   * May talk to the reader process, so don't call this in time-sensitive places.
   */
  fun resolve(uri: URI, context: PklProject?): VirtualFile? =
    stateFor(uri.scheme ?: return null, context)?.resolve(uri)

  /**
   * The specs of all readers that [context] (and the workspace settings) configure, by scheme.
   *
   * Talks to the reader processes, so don't call this in time-sensitive places.
   */
  fun readerSpecs(context: PklProject?): Map<String, ModuleReaderSpec> {
    val schemes = buildSet {
      addAll(project.settingsManager.settings.externalModuleReaders.keys)
      context?.metadata?.evaluatorSettings?.externalModuleReaders?.keys?.let { addAll(it) }
    }
    return buildMap {
      for (scheme in schemes) {
        stateFor(scheme, context)?.spec()?.let { put(scheme, it) }
      }
    }
  }

  /**
   * Checks the reader that [context] declares for [scheme]. Returns `null` if [context] doesn't
   * declare one, e.g. because it wasn't synced yet.
   *
   * Starts a process of its own (and stops it again), so that this isn't affected by [invalidate].
   * Blocks for up to [CHECK_TIMEOUT_SECONDS] on the process; don't call this where time matters.
   */
  fun checkProjectReader(scheme: String, kind: ReaderKind, context: PklProject): ReaderCheck? {
    val reader = projectReader(scheme, context, kind) ?: return null
    return checkReader(reader, scheme, kind)
  }

  private fun checkReader(reader: ExternalReader, scheme: String, kind: ReaderKind): ReaderCheck {
    val process = ExternalReaderProcess.of(reader)
    process.use { process ->
      // The reader talks to a child process, so give up on it if it never answers.
      val answer =
        checkExecutor.submit<Boolean> {
          when (kind) {
            ReaderKind.MODULE -> process.getModuleReaderSpec(scheme)
            ReaderKind.RESOURCE -> process.getResourceReaderSpec(scheme)
          } != null
        }
      return try {
        if (answer.get(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) ReaderCheck.Supported
        else ReaderCheck.UnsupportedScheme
      } catch (_: TimeoutException) {
        answer.cancel(true)
        ReaderCheck.NotAReader("it didn't answer within $CHECK_TIMEOUT_SECONDS seconds")
      } catch (e: ExecutionException) {
        classifyFailure(e.cause ?: e)
      }
    }
  }

  private fun classifyFailure(failure: Throwable): ReaderCheck {
    // Failing to start the process is reported as an `ExternalReaderProcessException` that wraps
    // the `IOException` from `ProcessBuilder.start`.
    val startFailure =
      generateSequence(failure) { it.cause }
        .filterIsInstance<ExternalReaderProcessException>()
        .firstOrNull { it.cause is IOException }
    if (startFailure != null) {
      return ReaderCheck.CannotStart(startFailure.cause?.message ?: startFailure.message ?: "")
    }
    val cause = generateSequence(failure) { it.cause }.last()
    return ReaderCheck.NotAReader(cause.message ?: cause.toString())
  }

  /** The spec of the reader for [scheme]; `null` if there is no such reader. */
  fun spec(scheme: String, context: PklProject?): ModuleReaderSpec? =
    stateFor(scheme, context)?.spec()

  /** Lists the children of the directory [uri]; empty if the scheme isn't hierarchical. */
  fun listChildren(uri: URI, context: PklProject?): List<VirtualFile> =
    stateFor(uri.scheme ?: return emptyList(), context)?.listChildren(uri) ?: emptyList()

  /** Lists every module of a scheme with non-hierarchical URIs. */
  fun listOpaqueModules(scheme: String, context: PklProject?): List<VirtualFile> =
    stateFor(scheme, context)?.listOpaqueModules() ?: emptyList()

  /**
   * Forgets everything that was learned from external readers: stops their processes and discards
   * their specs, listings, and files.
   *
   * Modules are fetched again on demand.
   */
  fun invalidate() {
    reset()
    // Cached values that depend on what readers resolve to are stale now.
    modificationTracker.increment()
    project.messageBus.emit(projectTopic, ProjectEvent.ExternalReadersChanged)
  }

  private fun reset() {
    val oldProcesses = processes.values.toList()
    processes.clear()
    val oldStates = states.values.toList()
    states.clear()
    // Files that were handed out can still be referenced (e.g. by cached values); this keeps them
    // from holding on to what the states know, or from starting new processes.
    for (state in oldStates) {
      state.close()
    }
    for (process in oldProcesses) {
      runCatching { process.close() }
    }
  }

  // (Doesn't shut down [checkExecutor]: its threads are daemons that time out when idle, and
  // components are initialized again after being disposed.)
  override fun dispose() {
    reset()
  }

  /**
   * Changes whenever readers change (see [invalidate]).
   *
   * Cached values that depend on modules resolved through external readers must depend on this,
   * because they're otherwise only invalidated by changes to the files they've seen; an import that
   * didn't resolve has no file to watch.
   */
  val modificationTracker = SimpleModificationTracker()

  /** The workspace-level readers as of the last time they were checked. */
  @Volatile private var lastWorkspaceReaders: Map<String, WorkspaceReader> = emptyMap()

  /**
   * Invalidates everything if the workspace settings' readers changed.
   *
   * Called by [SettingsManager] when settings are updated. Doing this eagerly (instead of when a
   * reader is looked up) is necessary because a cached resolution never looks up the reader.
   */
  fun onSettingsChanged() {
    val current = project.settingsManager.settings.externalModuleReaders
    if (current == lastWorkspaceReaders) return
    lastWorkspaceReaders = current
    invalidate()
    checkWorkspaceReaders(current)
  }

  /**
   * Checks the readers declared by the workspace settings, and tells the client about the ones that
   * don't work.
   *
   * Runs in the background, because it starts reader processes.
   */
  private fun checkWorkspaceReaders(readers: Map<String, WorkspaceReader>) {
    for (scheme in readers.keys) {
      val reader = workspaceReader(scheme) ?: continue
      checkExecutor.execute {
        try {
          val problem =
            checkReader(reader, scheme, ReaderKind.MODULE).problem(scheme) ?: return@execute
          notifyInvalidWorkspaceReader(scheme, problem)
        } catch (e: Exception) {
          logger.warn("Failed to check external reader for $scheme: ${e.stackTraceToString()}")
        }
      }
    }
  }

  private fun notifyInvalidWorkspaceReader(scheme: String, problem: String) {
    val message = "Invalid external module reader for scheme \"$scheme\". $problem."
    val extended = project.clientCapabilities.extended
    if (extended.actionableRuntimeNotifications == true && extended.pklConfigureCommand == true) {
      project.languageClient.sendActionableNotification(
        ActionableNotification(
          type = MessageType.Warning,
          message = message,
          commands =
            listOf(Command("Configure external readers", "pkl.configure", listOf(SETTING_NAME))),
        )
      )
    } else {
      project.languageClient.showMessage(MessageParams(MessageType.Warning, message))
    }
  }

  /** The reader for [scheme]; the one that [context] declares takes precedence. */
  private fun configuredReader(scheme: String, context: PklProject?): ExternalReader? {
    return context?.let { projectReader(scheme, it) } ?: workspaceReader(scheme)
  }

  /**
   * The reader declared by the workspace settings for [scheme].
   *
   * A relative executable or working directory is resolved against the workspace roots; the first
   * root that it exists in wins. An executable without a path separator (e.g. `my-reader`) is
   * looked up on the `PATH`, so it's left alone.
   */
  private fun workspaceReader(scheme: String): ExternalReader? {
    val declared = project.settingsManager.settings.externalModuleReaders[scheme] ?: return null
    return ExternalReader(
      resolveAgainstWorkspaceRoots(declared.executable, isExecutable = true),
      declared.arguments,
      declared.workingDir?.let { resolveAgainstWorkspaceRoots(it, isExecutable = false) },
    )
  }

  private fun resolveAgainstWorkspaceRoots(path: String, isExecutable: Boolean): String {
    if (isExecutable && !path.contains('/') && !path.contains('\\')) return path
    val asPath = runCatching { Path.of(path) }.getOrNull() ?: return path
    if (asPath.isAbsolute) return path
    return project.settingsManager.workspaceRoots
      .map { it.resolve(asPath).normalize() }
      .firstOrNull(Files::exists)
      ?.toString() ?: path
  }

  /**
   * A reader declared by [context].
   *
   * The working directory is taken from `resolvedEvaluatorSettings` if possible (Pkl 0.32+),
   * because it was resolved in-language and is therefore absolute. Otherwise, relative paths (the
   * working directory, and an executable that's a relative path) are resolved against the project's
   * directory.
   *
   * This behavior deviates from Pkl < 0.32, which resolves relative paths against PWD. However, the
   * old behavior is ostensibly incorrect, and there's no way to figure out what the intended PWD is
   * supposed to be.
   */
  private fun projectReader(
    scheme: String,
    context: PklProject,
    kind: ReaderKind = ReaderKind.MODULE,
  ): ExternalReader? {
    val metadata = context.metadata
    val declared = metadata.evaluatorSettings?.let { kind.readers(it) }?.get(scheme) ?: return null
    val resolved = metadata.resolvedEvaluatorSettings?.let { kind.readers(it) }?.get(scheme)
    return ExternalReader(
      resolved?.executable ?: resolveAgainst(context.projectDir, declared.executable, true),
      resolved?.arguments ?: declared.arguments,
      resolved?.workingDir
        ?: declared.workingDir?.let { resolveAgainst(context.projectDir, it, false) },
    )
  }

  /**
   * Resolves [path] against [dir] if it's a relative path.
   *
   * An executable without a path separator (e.g. `my-reader`) is a command that's looked up on the
   * `PATH`, not a relative path, so it's left alone.
   */
  private fun resolveAgainst(dir: Path, path: String, isExecutable: Boolean): String {
    if (isExecutable && !path.contains('/') && !path.contains('\\')) return path
    return runCatching {
        val asPath = Path.of(path)
        if (asPath.isAbsolute) path else dir.resolve(asPath).normalize().toString()
      }
      .getOrDefault(path)
  }

  /** The state of the reader that a cached file (or directory) belongs to. */
  private fun stateFor(scheme: String, context: PklProject?): ReaderState? {
    // The state remembers the project that declared the reader (not the project that happened to
    // ask), so that files read through it resolve their own imports with the same readers.
    val projectReader = context?.let { projectReader(scheme, it) }
    val reader = projectReader ?: workspaceReader(scheme) ?: return null
    val declaringProject = if (projectReader != null) context else null
    return states.computeIfAbsent(ReaderKey(scheme, reader)) {
      ReaderState(scheme, reader, processes, project, declaringProject)
    }
  }
}
