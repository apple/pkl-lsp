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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.isExecutable
import kotlinx.serialization.Serializable
import org.eclipse.lsp4j.*
import org.pkl.formatter.GrammarVersion
import org.pkl.lsp.*
import org.pkl.lsp.messages.ActionableNotification

@Serializable
data class WorkspaceSettings(
  var pklCliPath: Path? = null,
  var grammarVersion: GrammarVersion? = null,
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
    if (value.isJsonNull) return listOf()
    if (!value.isJsonArray) {
      logger.warn("Got non-array value for configuration: pkl.modulepath. Value: $value")
      return listOf()
    }
    return buildList {
      for (path in value.asJsonArray) {
        val decodedPath = decodeString(path, "pkl.modulepath")
        if (path != null) {
          val entry = Path.of(path.asString)
          if (Files.exists(entry)) add(entry)
          else logger.warn("Entry in pkl.modulepath does not exist: $entry")
        }
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
        )
      )
    return project.languageClient
      .configuration(params)
      .thenApply { (cliPath, grammarVersion, modulepath) ->
        logger.log(
          "Got configuration: cliPath = $cliPath, grammarVersion = $grammarVersion, modulepath = $modulepath"
        )
        settings.pklCliPath = resolvePklCliPath(cliPath as JsonElement)
        settings.grammarVersion = resolveGrammarVersion(grammarVersion as JsonElement)
        settings.pklModulepath = resolveModulepath(modulepath as JsonElement)
      }
      .exceptionally { logger.error("Failed to fetch settings: ${it.cause}") }
      .whenComplete { _, _ -> logger.log("Settings changed to $settings") }
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
