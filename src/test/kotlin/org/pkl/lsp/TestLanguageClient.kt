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
package org.pkl.lsp

import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.name
import org.eclipse.lsp4j.*
import org.pkl.lsp.messages.ActionableNotification

object TestLanguageClient : PklLanguageClient {
  lateinit var testProjectDir: Path

  val actionableNotifications: MutableList<ActionableNotification> = mutableListOf()

  val settings: MutableMap<Pair<String, String>, String> = mutableMapOf()

  fun reset() {
    actionableNotifications.clear()
  }

  override fun sendActionableNotification(params: ActionableNotification) {
    actionableNotifications.add(params)
  }

  override fun telemetryEvent(`object`: Any?) {
    // no-op
  }

  override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) {
    // no-op
  }

  override fun showMessage(messageParams: MessageParams?) {
    // no-op
  }

  override fun showMessageRequest(
    requestParams: ShowMessageRequestParams?
  ): CompletableFuture<MessageActionItem> {
    // no-op
    return CompletableFuture()
  }

  override fun logMessage(message: MessageParams) {
    if (message.type == MessageType.Info || message.type == MessageType.Log) return
    System.err.println(message.message)
  }

  override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> {
    return CompletableFuture.completedFuture(
      listOf(WorkspaceFolder(testProjectDir.toUri().toString(), testProjectDir.name))
    )
  }

  override fun configuration(
    configurationParams: ConfigurationParams
  ): CompletableFuture<List<Any>> {
    return CompletableFuture.completedFuture(
      configurationParams.items.map { item ->
        settings[item.scopeUri to item.section]?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE
      }
    )
  }
}
