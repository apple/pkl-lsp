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
package org.pkl.lsp.externalreader.fixture

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.pkl.messaging.MessagePackDecoder
import org.pkl.messaging.MessagePackEncoder
import org.pkl.messaging.MessageTransports
import org.pkl.messaging.PathElement
import org.pkl.messaging.externalreader.ExternalModuleReader
import org.pkl.messaging.externalreader.ExternalReaderClient

class FixtureModuleReader(private val scheme: String = DEFAULT_SCHEME) : ExternalModuleReader {
  companion object {
    const val DEFAULT_SCHEME = "fixture"

    val MODULES: Map<String, String> by lazy {
      val root =
        Path.of(
          FixtureModuleReader::class.java.getResource("/modules")?.toURI()
            ?: throw IllegalStateException("The modules directory is missing from the classpath")
        )
      Files.walk(root).use { paths ->
        paths
          .filter { it.isRegularFile() }
          .toList()
          .associate { "/" + root.relativize(it).invariantSeparatorsPathString to it.readText() }
          .toSortedMap()
      }
    }
  }

  override fun scheme(): String = scheme

  override fun hasHierarchicalUris(): Boolean = true

  override fun isGlobbable(): Boolean = true

  override fun isLocal(): Boolean = true

  override fun read(uri: URI): String =
    MODULES[uri.path] ?: throw IOException("No such module: $uri")

  override fun listElements(uri: URI): List<PathElement> {
    val directory = (uri.path ?: "/").let { if (it.endsWith("/")) it else "$it/" }
    val children = linkedMapOf<String, PathElement>()
    for (path in MODULES.keys) {
      if (!path.startsWith(directory)) continue
      val rest = path.removePrefix(directory)
      val name = rest.substringBefore('/')
      val isDirectory = rest.contains('/')
      // a name that's a directory for any of its modules is a directory
      if (isDirectory || name !in children) {
        children[name] = PathElement(name, isDirectory)
      }
    }
    if (children.isEmpty()) throw IOException("No such directory: $uri")
    return children.values.toList()
  }
}

/**
 * Runs [FixtureModuleReader] as an external reader process, speaking the external reader protocol
 * over stdin/stdout.
 *
 * The scheme to serve can be passed as the first argument (default: `fixture`).
 */
fun main(args: Array<String>) {
  val scheme = args.firstOrNull() ?: FixtureModuleReader.DEFAULT_SCHEME
  val transport =
    MessageTransports.stream(
      MessagePackDecoder(System.`in`),
      MessagePackEncoder(System.out),
      MessageTransports.Logger {},
    )
  ExternalReaderClient(listOf(FixtureModuleReader(scheme)), emptyList(), transport).run()
}
