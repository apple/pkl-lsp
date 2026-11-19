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
package org.pkl.lsp.services.externalreader

/** The result of checking whether a declared external reader works. */
sealed class ReaderCheck {
  /** A user-facing description of what's wrong, or `null` if nothing is. */
  abstract fun problem(scheme: String): String?

  /** The reader supports the scheme. */
  object Supported : ReaderCheck() {
    override fun problem(scheme: String): String? = null
  }

  /** The reader works, but doesn't handle the scheme (it answered without a spec). */
  object UnsupportedScheme : ReaderCheck() {
    override fun problem(scheme: String): String =
      "This reader does not support the scheme \"$scheme\""
  }

  /** The executable couldn't be started (e.g. it doesn't exist, or isn't executable). */
  class CannotStart(val message: String) : ReaderCheck() {
    override fun problem(scheme: String): String = "The executable can't be started: $message"
  }

  /**
   * The executable started but isn't an external reader: it didn't answer, closed before answering,
   * or answered with something that isn't the reader protocol.
   */
  class NotAReader(val message: String) : ReaderCheck() {
    override fun problem(scheme: String): String =
      "The executable isn't an external reader: $message"
  }
}
