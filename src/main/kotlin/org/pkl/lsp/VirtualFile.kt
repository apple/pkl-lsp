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
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.HashMap
import kotlin.io.path.name
import kotlin.io.path.readText
import org.pkl.core.util.IoUtils
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.util.CachedValue

enum class Origin {
  FILE,
  STDLIB,
  HTTPS,
  PACKAGE,
  JAR,
  MODULEPATH;

  companion object {
    fun fromString(origin: String): Origin? {
      return try {
        valueOf(origin)
      } catch (_: IllegalArgumentException) {
        null
      }
    }
  }
}

interface VirtualFile {
  val name: String
  val uri: URI
  val pklAuthority: String
  val project: Project

  fun parent(): VirtualFile?

  fun resolve(path: String): VirtualFile?

  fun toModule(): PklModule?

  fun contents(): String

  companion object {
    fun fromUri(uri: URI, project: Project): VirtualFile? {
      val logger = project.getLogger(this::class)
      return if (uri.scheme.equals("file", ignoreCase = true)) {
        FsFile(Path.of(uri), project)
      } else if (uri.scheme.equals("pkl", ignoreCase = true)) {
        val origin = Origin.fromString(uri.authority.uppercase())
        if (origin == null) {
          logger.error("Invalid origin for pkl url: ${uri.authority}")
          return null
        }
        val path = uri.path.drop(1)
        when (origin) {
          Origin.FILE -> FsFile(Path.of(path), project)
          Origin.STDLIB -> StdlibFile(path.replace(".pkl", ""), project)
          Origin.HTTPS -> HttpsFile(URI.create(path), project)
          Origin.JAR -> JarFile(URI(path), project)
          else -> {
            logger.error("Origin $origin is not supported")
            null
          }
        }
      } else null
    }
  }
}

class FsFile(val path: Path, override val project: Project) : VirtualFile {
  override val name: String = path.name
  override val uri: URI = path.toUri()
  override val pklAuthority: String = Origin.FILE.name.lowercase()

  override fun parent(): VirtualFile? = path.parent?.let { FsFile(it, project) }

  override fun resolve(path: String): VirtualFile? {
    val resolvedPath = this.path.resolve(path)
    return if (Files.exists(resolvedPath)) FsFile(this.path.resolve(path), project) else null
  }

  override fun toModule(): PklModule? {
    // get this module from the cache if possible so changes to it are propagated even
    // if the file was not saved
    return Builder.findModuleInCache(uri) ?: Builder.pathToModule(path, this)
  }

  override fun contents(): String = path.readText()
}

class StdlibFile(moduleName: String, override val project: Project) : VirtualFile {
  override val name: String = moduleName
  override val uri: URI = URI("pkl:$moduleName")
  override val pklAuthority: String = Origin.STDLIB.name.lowercase()

  override fun parent(): VirtualFile? = null

  override fun resolve(path: String): VirtualFile? = null

  override fun toModule(): PklModule? {
    return project.stdlib.getModule(name)
  }

  override fun contents(): String {
    return IoUtils.readClassPathResourceAsString(javaClass, "/org/pkl/core/stdlib/$name.pkl")
  }
}

class HttpsFile(override val uri: URI, override val project: Project) : VirtualFile {
  override val name: String = ""
  override val pklAuthority: String = Origin.HTTPS.name.lowercase()

  override fun parent(): VirtualFile {
    val newUri = if (uri.path.endsWith("/")) uri.resolve("..") else uri.resolve(".")
    return HttpsFile(newUri, project)
  }

  override fun resolve(path: String): VirtualFile {
    return HttpsFile(uri.resolve(path), project)
  }

  override fun toModule(): PklModule? {
    return project.fileCacheManager.findHttpModule(uri)
  }

  override fun contents(): String {
    return project.fileCacheManager.findHttpContent(uri) ?: ""
  }
}

private fun ensureJarFileSystem(uri: URI) {
  try {
    FileSystems.newFileSystem(uri, HashMap<String, Any>())
  } catch (e: FileSystemAlreadyExistsException) {
    FileSystems.getFileSystem(uri)
  }
}

class JarFile : VirtualFile {
  val path: Path
  override val uri: URI
  override val project: Project
  override val name: String
    get() = path.name

  override val pklAuthority: String
    get() = Origin.JAR.name.lowercase()

  constructor(path: Path, uri: URI, project: Project) {
    this.path = path
    this.uri = uri
    this.project = project
  }

  constructor(uri: URI, project: Project) {
    ensureJarFileSystem(uri)
    this.uri = uri
    this.path = Path.of(uri)
    this.project = project
  }

  override fun parent(): VirtualFile? = path.parent?.let { JarFile(it, it.toUri(), project) }

  override fun resolve(path: String): VirtualFile? {
    val resolvedPath = this.path.resolve(path)
    return if (Files.exists(resolvedPath)) JarFile(this.path.resolve(path).toUri(), project)
    else null
  }

  override fun toModule(): PklModule? {
    return Builder.findModuleInCache(uri) ?: Builder.pathToModule(path, this)
  }

  override fun contents(): String =
    project.cachedValuesManager.getCachedValue("${javaClass.simpleName}-contents-${uri}") {
      CachedValue(path.readText())
    }!!
}
