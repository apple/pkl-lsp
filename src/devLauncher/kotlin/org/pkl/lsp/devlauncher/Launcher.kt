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
package org.pkl.lsp.devlauncher

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.writeText
import org.pkl.lsp.PklLsp

class Launcher : CliktCommand() {
  private val pklCliPath: Path by
    option("--pkl-cli-path").path().defaultLazy { discoverPklCliPath() }

  private val workspace: Path by option("--workspace").path().defaultLazy { defaultWorkspace }

  private val projectRootDir by lazy {
    val workingDir = Path.of(System.getProperty("user.dir"))
    workingDir.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: workingDir.parent?.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: workingDir.parent?.parent?.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: throw AssertionError("Failed to locate root project directory.")
  }

  private val userDataDir: Path by lazy { projectRootDir.resolve("build/extensionHostData") }

  private val pklVscodeDir: Path by lazy { projectRootDir.resolve("../pkl-vscode") }

  // The target directory to open; configurable with ARGV.
  private val defaultWorkspace: Path by lazy { projectRootDir.resolve("../pkl-pantry") }

  private fun prepareUserDataDir(port: Int, pklCliPath: Path) {
    val settingsFile =
      userDataDir.resolve("User/settings.json").also { it.createParentDirectories() }
    settingsFile.writeText(
      """
      {
        "pkl.lsp.socket.port": $port,
        "pkl.cli.path": "$pklCliPath"
      }
    """
        .trimIndent()
    )
    userDataDir.createDirectories()
  }

  private fun launchVscode(workspace: Path) {
    ProcessBuilder(
        "code",
        "--extensionDevelopmentPath=${pklVscodeDir}",
        "--user-data-dir=${userDataDir}",
        workspace.absolutePathString(),
      )
      .start()
  }

  private fun discoverPklCliPath(): Path {
    val systemPath = System.getenv("PATH")
    val pathDirs = systemPath.split(File.pathSeparator).filter { !it.isEmpty() }
    for (pathDir in pathDirs) {
      val file = Path.of(pathDir).resolve("pkl")
      if (file.isExecutable()) {
        return file
      }
    }
    throw RuntimeException("Cannot find `pkl` CLI on PATH. Try setting it using `--pkl-cli-path`.")
  }

  override fun run() {
    val serverSocket = ServerSocket(0)
    prepareUserDataDir(serverSocket.localPort, pklCliPath)
    launchVscode(workspace)
    // wait until a connection is made from the client before starting the LSP
    val clientSocket = serverSocket.accept()
    PklLsp.run(true, clientSocket.inputStream, clientSocket.outputStream)
  }
}
