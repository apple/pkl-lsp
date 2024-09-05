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
package org.pkl.lsp

import java.net.URI
import java.nio.file.*
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class VirtualFileManager(project: Project) : Component(project) {
  override fun initialize(): CompletableFuture<*> {
    project.messageBus.subscribe(textDocumentTopic) { event ->
      val file = files[event.file] ?: return@subscribe
      if (event.type == TextDocumentEventType.CHANGED) {
        file.modificationCount.incrementAndGet()
      }
    }
    return CompletableFuture.completedFuture(Unit)
  }

  private val files: MutableMap<URI, BaseFile> = ConcurrentHashMap()

  fun get(path: Path): VirtualFile? {
    return if (!Files.exists(path)) null else get(path.toUri(), path)
  }

  fun get(uri: URI, path: Path? = null): VirtualFile? {
    val effectiveUri = uri.effectiveUri ?: return null
    val existing = files[effectiveUri]
    if (existing != null) {
      return existing
    }
    return create(effectiveUri, path)
  }

  fun getFsFile(path: Path): FsFile? = get(path) as? FsFile

  fun getFsFile(uri: URI): FsFile? = get(uri) as? FsFile

  /**
   * Creates a one-off virtual file; this file does not get managed (workspace events do not cause
   * this file modification count nor contents to change).
   */
  fun getEphemeral(text: String): VirtualFile = EphemeralFile(text, project)

  private fun create(uri: URI, path: Path? = null): VirtualFile? {
    val effectiveUri = uri.effectiveUri ?: return null
    val file =
      when (effectiveUri.scheme) {
        "file" -> FsFile(path ?: Path.of(effectiveUri), this.project)
        "jar" -> {
          ensureJarFileSystem(effectiveUri)
          JarFile(path ?: Path.of(effectiveUri), effectiveUri, project)
        }
        "pkl" -> StdlibFile(effectiveUri.schemeSpecificPart, project)
        else -> throw Exception("Unsupported scheme: ${effectiveUri.scheme}")
      }
    return file.also { files[effectiveUri] = file }
  }

  // This is technically a memory leak, albeit a mild one and should be tolerable.
  //
  // For every new package opened, its file system is never closed and kept on heap.
  private fun ensureJarFileSystem(uri: URI) {
    try {
      FileSystems.newFileSystem(uri, HashMap<String, Any>())
    } catch (e: FileSystemAlreadyExistsException) {
      FileSystems.getFileSystem(uri)
    }
  }
}
