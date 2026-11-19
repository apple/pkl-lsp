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

import java.io.IOException
import java.net.URI
import java.util.Collections.newSetFromMap
import java.util.concurrent.ConcurrentHashMap
import org.pkl.lsp.ExternalReaderFile
import org.pkl.lsp.Project
import org.pkl.lsp.VirtualFile
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.messaging.PathElement
import org.pkl.messaging.externalreader.ExternalReader
import org.pkl.messaging.externalreader.ExternalReaderProcess
import org.pkl.messaging.externalreader.ExternalReaderProcessException
import org.pkl.messaging.externalreader.ModuleReaderSpec

/**
 * Everything that's specific to one reader configuration (for one scheme): its spec, the files that
 * were handed out, and the bookkeeping of what failed.
 *
 * Unlike pkl-intellij, nothing is written to disk; module contents are held in memory by
 * [ExternalReaderFile].
 */
class ReaderState(
  val scheme: String,
  private val reader: ExternalReader,
  private val processes: MutableMap<ExternalReader, ExternalReaderProcess>,
  private val project: Project,
  /** The project that declares [reader]; `null` if it comes from the workspace settings. */
  val context: PklProject?,
) {
  private val logger = project.getLogger(this::class)

  /** Files that were handed out; ensures that a URI maps to one [VirtualFile] instance. */
  private val filesByUri: MutableMap<URI, ExternalReaderFile> = ConcurrentHashMap()

  /**
   * Set when this state was discarded (see [close]).
   *
   * Files that were handed out may outlive their state (e.g. in cached values). A closed state must
   * not start reader processes again, because its reader may not be configured any more.
   */
  @Volatile private var closed = false

  private val process: ExternalReaderProcess
    get() {
      if (closed) throw IOException("The external reader for $scheme was invalidated")
      return processes.computeIfAbsent(reader) { ExternalReaderProcess.of(it) }
    }

  /**
   * Discards everything that this state knows. Afterwards, it doesn't talk to the reader any more:
   * it knows no files, lists no children, and fails to read contents that weren't read already.
   *
   * The reader process itself is owned (and closed) by the service.
   */
  fun close() {
    closed = true
    cachedSpec = null
    filesByUri.clear()
    listings.clear()
    failed.clear()
  }

  @Volatile private var cachedSpec: ModuleReaderSpec? = null

  private val listings: MutableMap<URI, List<PathElement>> = ConcurrentHashMap()

  /** URIs that failed to be read; these aren't tried again until the service is invalidated. */
  private val failed: MutableSet<URI> = newSetFromMap(ConcurrentHashMap())

  /**
   * The URI that's sent to the reader to list *every* module of a scheme with non-hierarchical URIs
   * (similar to how `pkl:` lists every stdlib module).
   */
  private val opaqueListingUri: URI = URI("$scheme:dummy")

  /** Talks to the reader process, so don't call this in time-sensitive places. */
  fun spec(): ModuleReaderSpec? {
    if (closed) return null
    cachedSpec?.let {
      return it
    }
    return try {
      process.getModuleReaderSpec(scheme)?.also { cachedSpec = it }
    } catch (e: IOException) {
      logger.warn("Failed to get module reader spec for $scheme: ${e.message}")
      null
    } catch (e: ExternalReaderProcessException) {
      logger.warn("Failed to get module reader spec for $scheme: ${e.message}")
      null
    }
  }

  fun isHierarchical(): Boolean = spec()?.hasHierarchicalUris == true

  /**
   * Returns the file for [uri], or `null` if the reader doesn't have it.
   *
   * For hierarchical schemes, a URI whose path ends in `/` is a directory (which isn't checked for
   * existence).
   */
  fun resolve(uri: URI): VirtualFile? {
    if (closed) return null
    filesByUri[uri]?.let {
      return it
    }
    if (uri in failed) {
      return null
    }
    val isDirectory = uri.path?.endsWith("/") == true
    // Readers aren't required to report directories via `hasElement`, so directories are trusted;
    // listing a directory that doesn't exist yields no children.
    if (isDirectory) return fileFor(uri, isDirectory = true)
    return try {
      if (!process.getModuleResolver(1).hasElement(uri)) return null
      fileFor(uri, isDirectory = false)
    } catch (e: IOException) {
      failFetch(uri, e)
    } catch (e: ExternalReaderProcessException) {
      failFetch(uri, e)
    }
  }

  /** Reads a module's contents from the reader. */
  fun resolveContents(uri: URI): String = process.getModuleResolver(1).resolveModule(uri)

  /**
   * Lists the children of the directory [uri].
   *
   * Only hierarchical schemes have directories; use [listOpaqueModules] for the others.
   */
  fun listChildren(uri: URI): List<VirtualFile> {
    if (!isHierarchical()) return emptyList()
    // `URI.resolve` replaces the last path segment unless the base ends with a slash.
    val base = if (uri.path?.endsWith("/") == false) withPath(uri, uri.path + "/") else uri
    return listElements(base).map { element ->
      val suffix = if (element.isDirectory) "/" else ""
      fileFor(withPath(base, base.path + element.name + suffix), element.isDirectory)
    }
  }

  /** Lists every module of a scheme with non-hierarchical URIs. */
  fun listOpaqueModules(): List<VirtualFile> =
    listElements(opaqueListingUri)
      .filter { !it.isDirectory }
      .map { fileFor(URI(scheme, it.name, null), isDirectory = false) }

  private fun withPath(uri: URI, path: String): URI =
    URI(uri.scheme, uri.authority, path, uri.query, uri.fragment)

  private fun fileFor(uri: URI, isDirectory: Boolean): ExternalReaderFile =
    filesByUri.computeIfAbsent(uri) { ExternalReaderFile(uri, project, isDirectory, this) }

  /**
   * Asks the reader for [baseUri]'s children.
   *
   * An [IOException] or [ExternalReaderProcessException] (the reader can't list that URI, or its
   * process can't be started or is gone) is terminal: the result is remembered as empty until the
   * service is invalidated. Any other exception is logged, and the listing is tried again next
   * time.
   */
  private fun listElements(baseUri: URI): List<PathElement> {
    if (closed) return emptyList()
    listings[baseUri]?.let {
      return it
    }
    return try {
      process.getModuleResolver(0).listElements(baseUri).also { listings[baseUri] = it }
    } catch (e: IOException) {
      failListing(baseUri, e)
    } catch (e: ExternalReaderProcessException) {
      failListing(baseUri, e)
    } catch (e: Exception) {
      logger.warn(
        "Failed to list elements of $baseUri; will try again. Exception: ${e.message ?: e.stackTraceToString()}"
      )
      emptyList()
    }
  }

  private fun failListing(baseUri: URI, e: Exception): List<PathElement> {
    if (closed) return emptyList()
    logger.warn(
      "Failed to list elements of $baseUri (won't retry until cleared): ${e.message ?: e.stackTraceToString()}"
    )
    listings[baseUri] = emptyList()
    return emptyList()
  }

  private fun failFetch(uri: URI, e: Exception): VirtualFile? {
    if (closed) return null
    failed.add(uri)
    logger.warn(
      "Failed to read $uri from external reader (won't retry until cleared): ${e.message}"
    )
    return null
  }
}
