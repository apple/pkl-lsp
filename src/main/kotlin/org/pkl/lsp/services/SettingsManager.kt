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

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.isExecutable
import kotlinx.serialization.Serializable
import org.eclipse.lsp4j.*
import org.pkl.lsp.*
import org.pkl.lsp.messages.ActionableNotification

@Serializable
data class WorkspaceSettings(
  var pklCliPath: Path? = null,
  var pklModulepath: List<Path> = listOf(),
)

class SettingsManager(project: Project) : Component(project) {
  var settings: WorkspaceSettings = WorkspaceSettings()
  private lateinit var initialized: CompletableFuture<*>

  override fun initialize(): CompletableFuture<*> {
    initialized = loadSettings()
    project.messageBus.subscribe(textDocumentTopic, ::handleTextDocumentEvent)
    project.messageBus.subscribe(workspaceConfigurationChangedTopic) { loadSettings() }
    return initialized
  }

  override fun dispose() {
    initialized.cancel(true)
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
            section = "pkl.modulepath"
          },
        )
      )
    return project.languageClient.configuration(params).thenApply { (cliPath, modulepath) ->
      logger.log("Got configuration: { cli.path = $cliPath, modulepath = $modulepath }")
      setCliPath(cliPath as JsonElement)
      setModulepath(modulepath as JsonElement)
    }
  }

  private fun setCliPath(cliPath: JsonElement) {
    if (cliPath.isJsonNull) {
      settings.pklCliPath = findPklCliOnPath()
      return
    }
    if (!(cliPath is JsonPrimitive && cliPath.isString)) {
      logger.warn("Got non-string value for configuration: $cliPath")
      return
    }
    settings.pklCliPath =
      cliPath.asString.let { if (it.isEmpty()) findPklCliOnPath() else Path.of(it) }
  }

  private fun setModulepath(modulepath: JsonElement) {
    if (modulepath.isJsonNull) return
    if (!modulepath.isJsonArray) {
      logger.warn("Got non-array value for configuration: $modulepath")
      return
    }
    settings.pklModulepath = buildList {
      for (path in modulepath.asJsonArray) {
        if (!(path is JsonPrimitive && path.isString)) {
          logger.warn("Got non-string value in modulepath: $path")
        }
        val entry = Path.of(path.asString)
        if (Files.exists(entry)) add(entry)
        else logger.warn("Entry in modulepath does not exist: $entry")
      }
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
}
