/*
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

import org.eclipse.lsp4j.ClientCapabilities

data class PklClientCapabilities(
  /** Default capabilities as defined by the LSP spec. */
  val standard: ClientCapabilities,

  /** Additional capabilites defined by the Pkl LSP. */
  val extended: ExtendedClientCapabilities,
)

data class ExtendedClientCapabilities(
  /** This client supports the `"pkl/actionableNotification"` message. */
  val actionableRuntimeNotifications: Boolean? = null,

  /** This client supports the `"pkl.configure"` command. */
  val pklConfigureCommand: Boolean? = null,
)
