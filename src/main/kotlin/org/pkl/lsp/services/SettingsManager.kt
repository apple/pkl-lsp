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

import com.google.gson.JsonPrimitive
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.pkl.lsp.Component
import org.pkl.lsp.Project

@Serializable data class WorkspaceSettings(var pklCliPath: Path? = null)

class SettingsManager(project: Project) : Component(project) {
  var settings: WorkspaceSettings = WorkspaceSettings()

  fun loadSettings() {
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
    project.languageClient
      .configuration(params)
      .thenApply { response ->
        logger.log("Got $response from configuration request")
        val cliPath = response[0] as JsonPrimitive
        if (!cliPath.isString) {
          logger.log("Got non-string value for configuration: $cliPath")
          return@thenApply
        }
        settings.pklCliPath = Path.of(cliPath.asString)
        logger.log("Loaded settings: $settings")
      }
      .exceptionally { err -> logger.error("Failed to fetch configuration: ${err.cause}") }
  }
}
