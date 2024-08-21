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

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient

object FakeLanguageClient : LanguageClient {
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

  override fun logMessage(message: MessageParams?) {
    // no-op
  }
}
