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

package org.pkl.lsp.generatenativeimagemetadata

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
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

  person: Person = new {
    name = "Alice"
    age = "Not a correct age"
  }
  """
    .trimIndent()

/**
 * Run PklLsp in-process and have it parse a module and provide a diagnostic.
 *
 * This is used to generate GraalVM reachability metadata.
 * This main function executed with GraalVM's tracing agent enabled, which inspects reflection, foreign calls,
 * resources, etc.
 *
 * For reference, see [Collect Metadata with the Tracing Agent](https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/)
 */
fun main(args: Array<String>) {
  require(args.size == 1) { "Usage: <rootUri>" }
  val rootUri = args[0]
  val rootPath = Path.of(URI(rootUri)).also { it.createDirectories() }
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
            workspace =
              WorkspaceClientCapabilities().apply {
                workspaceFolders = true
                didChangeConfiguration = DidChangeConfigurationCapabilities(false)
              }
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
    val diag = client.getNextDiagnostic()
    assert(diag.diagnostics.size == 1)
    val message = diag.diagnostics.first().message.let { if (it.isLeft) it.left else it.right.value }
    assert(message.contains("Type mismatch"))
  } finally {
    server.shutdown().get()
    server.exit()
    serverThread.join()
  }
}
