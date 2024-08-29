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

import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlin.io.path.absolutePathString
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.packages.dto.PackageUri

class PklCliException(message: String, cause: Throwable?) : Throwable(message, cause) {
  constructor(message: String) : this(message, null)
}

class PklCli(project: Project) : Component(project) {
  fun downloadPackage(
    packages: List<PackageUri>,
    noTransitive: Boolean = true,
  ): CompletableFuture<Unit> {
    val args = buildList {
      add("download-package")
      if (noTransitive) {
        add("--no-transitive")
      }
      addAll(packages.map { it.toStringWithChecksum() })
    }
    return executeCommand(args).thenApply {}
  }

  private fun executeCommand(args: List<String>): CompletableFuture<String> {
    return CompletableFuture.supplyAsync {
      val cliPath =
        project.settingsManager.settings.pklCliPath?.absolutePathString()
          ?: throw PklCliException("Pkl CLI path is not set")
      logger.info("Spawning command `$cliPath` with arguments $args")
      val process =
        try {
          ProcessBuilder(cliPath, *args.toTypedArray()).start()
        } catch (e: IOException) {
          throw PklCliException(
            """
          Received IOException when spawning pkl:
          
          ${e.stackTraceToString()}
        """
              .trimIndent()
          )
        }
      val result = StringBuilder()
      val stderr = StringBuilder()
      process.inputStream.reader().forEachLine { line ->
        logger.log("\t${line}")
        result.appendLine(line)
      }
      process.errorStream.reader().forEachLine { line ->
        logger.error("\t[stderr]: $line")
        stderr.appendLine(line)
      }
      if (process.exitValue() == 0) {
        result.toString()
      } else {
        throw PklCliException(
          """
          Command exited with code ${process.exitValue()}.
          
          Error output:
          $stderr
          """
        )
      }
    }
  }
}
