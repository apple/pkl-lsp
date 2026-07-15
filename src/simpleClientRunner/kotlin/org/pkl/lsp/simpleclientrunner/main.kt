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
@file:JvmName("Main")

package org.pkl.lsp.simpleclientrunner

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.pkl.lsp.PklLsp
import org.pkl.lsp.commons.SimpleClient

// language=pkl
private val SAMPLE_PKL =
  """
  class Person {
    name: String
    age: Int
  }

  /// This is an [unresolved reference]
  person: Person = new {
    name = "Alice"
  }
  """
    .trimIndent()

/**
 * Run pkl-lsp in-process and have it parse a module and provide a diagnostic.
 *
 * This is used to generate GraalVM reachability metadata. This main function executed with
 * GraalVM's tracing agent enabled, which inspects reflection, foreign calls, resources, etc.
 *
 * For reference, see
 * [Collect Metadata with the Tracing Agent](https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/)
 */
fun main(args: Array<String>) {
  require(args.size == 1) { "Usage: <rootDir>" }
  val rootPath = Path.of(args[0]).also { it.createDirectories() }
  val rootUri = rootPath.toUri().toString()
  val samplePklFile = rootPath.resolve("sample.pkl").also { it.writeText(SAMPLE_PKL) }

  val clientToServerOut = PipedOutputStream()
  val clientToServerIn = PipedInputStream(clientToServerOut)
  val serverToClientOut = PipedOutputStream()
  val serverToClientIn = PipedInputStream(serverToClientOut)

  val serverThread =
    Thread({ PklLsp.run(verbose = false, clientToServerIn, serverToClientOut) }, "pkl-lsp-server")
  serverThread.start()

  val client = SimpleClient()
  val launcher = LSPLauncher.createClientLauncher(client, serverToClientIn, clientToServerOut)
  launcher.startListening()
  val server = launcher.remoteProxy

  server
    .initialize(
      InitializeParams().apply {
        setRootUri(rootUri)
        workspaceFolders = listOf(WorkspaceFolder(rootUri, "pkl-lsp"))
        capabilities =
          ClientCapabilities().apply {
            workspace = WorkspaceClientCapabilities().apply { workspaceFolders = true }
          }
      }
    )
    .get()
  server.initialized(InitializedParams())

  server.textDocumentService.didOpen(
    DidOpenTextDocumentParams(
      TextDocumentItem(samplePklFile.toUri().toString(), "pkl", 1, SAMPLE_PKL)
    )
  )

  try {
    // trigger diagnostic to capture tracing of tree-sitter and flexmark parsing
    // flexmark is used for parsing member links in markdown comments.
    // getting a diagnostic around an unresolved reference means that:
    // 1. markdown was parsed
    // 2. Pkl code was parsed
    val diag = client.getNextDiagnostic()
    assert(diag.diagnostics.size == 1)
    val message =
      diag.diagnostics.first().message.let { if (it.isLeft) it.left else it.right.value }
    assert(message.contains("unresolved reference"))
  } finally {
    server.shutdown().get()
    server.exit()
    serverThread.join()
  }
}
