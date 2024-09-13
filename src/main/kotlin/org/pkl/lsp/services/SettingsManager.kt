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

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlinx.serialization.Serializable
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.MessageType
import org.pkl.lsp.*
import org.pkl.lsp.messages.ActionableNotification

@Serializable data class WorkspaceSettings(var pklCliPath: Path? = null)

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
          }
        )
      )
    return project.languageClient
      .configuration(params)
      .thenApply { (cliPath) ->
        logger.log("Got configuration: $cliPath")
        cliPath as JsonElement
        if (cliPath.isJsonNull) {
          settings.pklCliPath = null
          return@thenApply
        }
        if (!(cliPath is JsonPrimitive && cliPath.isString)) {
          logger.warn("Got non-string value for configuration: $cliPath")
          return@thenApply
        }
        settings.pklCliPath = cliPath.asString.let { if (it.isEmpty()) null else Path.of(it) }
      }
      .exceptionally { logger.error("Failed to fetch settings: ${it.cause}") }
      .whenComplete { _, _ -> logger.log("Settings changed to $settings") }
  }

  private fun handleTextDocumentEvent(event: TextDocumentEvent) {
    if (event !is TextDocumentEvent.Opened) return
    val file = project.virtualFileManager.getFsFile(event.file) ?: return
    initialized.whenComplete { _, _ ->
      if (settings.pklCliPath == null && file.pklProjectDir != null) {
        project.languageClient.sendActionableNotification(
          ActionableNotification(
            type = MessageType.Info,
            message = "Pkl CLI is not configured",
            commands =
              listOf(Command("Configure CLI path", "pkl.configure", listOf("pkl.cli.path"))),
          )
        )
      }
    }
  }
}
