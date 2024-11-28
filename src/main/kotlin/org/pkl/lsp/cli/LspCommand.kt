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
package org.pkl.lsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import org.pkl.lsp.PklLsp
import org.pkl.lsp.Release

class LspCommand : CliktCommand(name = "pkl-lsp") {
  init {
    versionOption(version = Release.version)
  }

  private val verbose: Boolean by
    option(names = arrayOf("--verbose"), help = "Send debug information to the client")
      .flag(default = false)

  override fun help(context: Context): String =
    "Run a Language Server Protocol server for Pkl that communicates over standard input/output"

  override fun run() {
    PklLsp.run(verbose)
  }
}
