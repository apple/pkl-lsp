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
package org.pkl.lsp

public const val DEFAULT_PKL_AUTHORITY = "pkg.pkl-lang.org"

/** Additional options passed by a language client when initializing the LSP. */
data class PklClientOptions(
  /**
   * Cause documentation to render URIs in the form of `command:pkl.open.file?[URI,line,column]`.
   */
  val renderOpenFileCommandInDocs: Boolean = false,

  /** Additional capabilities supported by this client. */
  val extendedClientCapabilities: ExtendedClientCapabilities = ExtendedClientCapabilities(),

  /**
   * Map of package server authorities to their documentation URL patterns. The URL pattern should
   * contain placeholders: {packagePath}, {version}, {modulePath}, {path} Example:
   * "https://pkl-lang.org/package-docs/{packagePath}/{version}/{modulePath}/{path}"
   */
  val packageDocumentationUrls: Map<String, String> =
    mapOf(
      DEFAULT_PKL_AUTHORITY to
        "https://pkl-lang.org/package-docs/{packagePath}/{version}/{modulePath}/{path}"
    ),
) {
  companion object {
    val default = PklClientOptions()
  }
}
