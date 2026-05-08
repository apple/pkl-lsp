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

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isExecutable
import kotlinx.serialization.Serializable
import org.eclipse.lsp4j.*
import org.pkl.formatter.GrammarVersion
import org.pkl.lsp.*
import org.pkl.lsp.messages.ActionableNotification
import org.pkl.lsp.util.ModificationTracker

@Serializable
data class WorkspaceSettings(
  var pklCliPath: Path? = null,
  var grammarVersion: GrammarVersion? = null,
  var modulepath: List<Path> = emptyList(),
  var excludedDirectories: List<String> = emptyList(),
)

class SettingsManager(project: Project) : Component(project), ModificationTracker {
  var settings: WorkspaceSettings = WorkspaceSettings()
  private lateinit var initialized: CompletableFuture<*>
  private val updateCount = AtomicInteger(0)

  private val workspaceFolders: MutableSet<Path> = mutableSetOf()

  fun initialize(folders: List<Path>?) {
    project.messageBus.subscribe(textDocumentTopic, ::handleTextDocumentEvent)
    project.messageBus.subscribe(workspaceConfigurationChangedTopic) { loadSettings() }
    project.messageBus.subscribe(workspaceFolderTopic, ::handleWorkspaceFolderEvent)
    if (folders != null) {
      workspaceFolders.addAll(folders)
    }
  }

  override fun initialize(): CompletableFuture<*> = loadSettings().also { initialized = it }

  override fun dispose() {
    initialized.cancel(true)
  }

  private fun decodeString(value: JsonElement, configName: String): String? {
    if (value.isJsonNull) {
      return null
    }
    if (value !is JsonPrimitive || !value.isString) {
      logger.warn("Got non-string value for configuration: $configName. Value: $value")
    }
    return value.asString.ifEmpty { null }
  }

  private fun resolvePklCliPath(settingsValue: JsonElement): Path? {
    val decodedCliPath = decodeString(settingsValue, "pkl.cli.path")
    if (decodedCliPath != null) {
      return Path.of(decodedCliPath)
    }
    return findPklCliOnPath()
  }

  private fun resolveGrammarVersion(value: JsonElement): GrammarVersion {
    return when (val str = decodeString(value, "pkl.formatter.grammarVersion")) {
      null -> GrammarVersion.latest()
      "1" -> GrammarVersion.V1
      "2" -> GrammarVersion.V2
      else -> {
        logger.warn("Got invalid value for pkl.formatter.grammarVersion: $str")
        GrammarVersion.latest()
      }
    }
  }

  private fun resolveModulepath(value: JsonElement): List<Path> {
    if (value.isJsonNull) return emptyList()
    if (!value.isJsonArray) {
      logger.warn("Got non-array value for configuration: pkl.modulepath. Value: $value")
      return emptyList()
    }
    return value.asJsonArray
      .mapNotNull { decodeString(it, "pkl.modulepath[]") }
      .mapNotNull { path ->
        val resolvedPath =
          try {
            Path.of(path)
          } catch (e: InvalidPathException) {
            logger.warn("Configured module path $path is invalid: ${e.reason}")
            return@mapNotNull null
          }
        when {
          resolvedPath.isAbsolute -> resolvedPath
          else -> workspaceFolders.map { it.resolve(resolvedPath) }.firstOrNull(Files::exists)
        }
      }
  }

  private fun resolveExcludedDirectories(value: JsonElement): List<String> {
    if (value.isJsonNull) {
      return emptyList()
    }
    if (!value.isJsonArray) {
      logger.warn(
        "Got non-array value for configuration: pkl.projects.excludedDirectories. Value: $value"
      )
      return emptyList()
    }
    return value.asJsonArray.mapNotNull { element ->
      if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
        element.asString.ifEmpty { null }
      } else {
        logger.warn("Got non-string element in pkl.projects.excludedDirectories: $element")
        null
      }
    }
  }

  private fun loadSettings(): CompletableFuture<Unit> {
    logger.log("Fetching configuration")
    val params =
      ConfigurationParams(
        listOf(
          ConfigurationItem().apply {
            scopeUri = "Pkl"
            section = "pkl.cli.path"
          },
          ConfigurationItem().apply {
            scopeUri = "Pkl"
            section = "pkl.formatter.grammarVersion"
          },
          ConfigurationItem().apply {
            scopeUri = "Pkl"
            section = "pkl.modulepath"
          },
          ConfigurationItem().apply {
            scopeUri = "Pkl"
            section = "pkl.projects.excludedDirectories"
          },
        )
      )
    return project.languageClient
      .configuration(params)
      .thenApply { (cliPath, grammarVersion, modulepath, excludedDirectories) ->
        logger.log(
          buildString {
            append("Got configuration: ")
            append("cliPath = $cliPath, ")
            append("grammarVersion = $grammarVersion, ")
            append("modulepath = $modulepath, ")
            append("excludedDirectories = $excludedDirectories")
          }
        )
        settings.pklCliPath = resolvePklCliPath(cliPath as JsonElement)
        settings.grammarVersion = resolveGrammarVersion(grammarVersion as JsonElement)
        settings.modulepath = resolveModulepath(modulepath as JsonElement)
        settings.excludedDirectories =
          resolveExcludedDirectories(excludedDirectories as JsonElement)
      }
      .exceptionally { logger.error("Failed to fetch settings: ${it.cause}") }
      .whenComplete { _, _ ->
        logger.log("Settings changed to $settings")
        updateCount.incrementAndGet()
      }
  }

  private fun findPklCliOnPath(): Path? {
    val pathDirs = System.getenv("PATH").split(if (isWindows) ";" else ":")
    for (dir in pathDirs) {
      val file = Path.of(dir).resolve("pkl")
      if (file.isExecutable()) {
        logger.log("Using Pkl CLI on PATH: $file")
        return file
      }
    }
    return null
  }

  private fun handleTextDocumentEvent(event: TextDocumentEvent) {
    if (event !is TextDocumentEvent.Opened) return
    val file = project.virtualFileManager.getFsFile(event.file) ?: return
    initialized.whenComplete { _, _ ->
      if (settings.pklCliPath == null && file.pklProjectDir != null) {
        if (
          project.clientCapabilities.extended.actionableRuntimeNotifications == true &&
            project.clientCapabilities.extended.pklConfigureCommand == true
        ) {
          project.languageClient.sendActionableNotification(
            ActionableNotification(
              type = MessageType.Info,
              message = ErrorMessages.create("pklCliNotConfigured"),
              commands =
                listOf(Command("Configure CLI path", "pkl.configure", listOf("pkl.cli.path"))),
            )
          )
        } else {
          project.languageClient.showMessage(
            MessageParams(MessageType.Info, ErrorMessages.create("pklCliNotFound"))
          )
        }
      }
    }
  }

  private fun handleWorkspaceFolderEvent(event: WorkspaceFoldersChangeEvent) {
    synchronized(this) {
      for (workspaceFolder in event.added) {
        val path = Path.of(URI(workspaceFolder.uri))
        workspaceFolders.add(path)
      }
      for (workspaceFolder in event.removed) {
        val path = Path.of(URI(workspaceFolder.uri))
        workspaceFolders.removeIf { it.toUri() == path.toUri() }
      }
    }
    initialized = loadSettings()
  }

  override fun getModificationCount(): Long = updateCount.get().toLong()
}
