/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.types.path
import org.pkl.lsp.debug.AstDumper
import kotlin.io.path.exists

class DebugCommand : CliktCommand(name = "debug") {
  
  private val files by argument(
    help = "PKL files to dump AST for"
  ).path(mustExist = true).multiple(required = true)
  
  override fun help(context: Context): String =
    "Debug utility to dump AST structure and metadata for PKL files"

  override fun run() {
    val dumper = AstDumper()
    
    files.forEach { file ->
      if (file.exists()) {
        dumper.dumpFile(file)
      } else {
        echo("ERROR: File does not exist: $file", err = true)
      }
    }
  }
}
