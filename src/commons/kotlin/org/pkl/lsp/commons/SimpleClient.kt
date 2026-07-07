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
package org.pkl.lsp.commons

import com.google.gson.JsonNull
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient

/** A minimal [LanguageClient] that provides a handler to retrieve diagnostics. */
class SimpleClient : LanguageClient {
  fun getNextDiagnostic() = diagnosticsParams.poll(3, TimeUnit.SECONDS) as PublishDiagnosticsParams

  private val diagnosticsParams: BlockingQueue<PublishDiagnosticsParams> = ArrayBlockingQueue(10)

  override fun configuration(
    configurationParams: ConfigurationParams
  ): CompletableFuture<List<Any>> {
    return CompletableFuture.completedFuture(configurationParams.items.map { JsonNull.INSTANCE })
  }

  override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
    diagnosticsParams.add(diagnostics)
  }

  override fun telemetryEvent(`object`: Any?) {
    throw NotImplementedError()
  }

  override fun showMessage(messageParams: MessageParams?) {
    throw NotImplementedError()
  }

  override fun showMessageRequest(
    requestParams: ShowMessageRequestParams?
  ): CompletableFuture<MessageActionItem?>? {
    throw NotImplementedError()
  }

  override fun logMessage(message: MessageParams?) {
    throw NotImplementedError()
  }
}
