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

import java.io.File
import java.io.IOException
import java.net.URI
import org.pkl.lsp.ast.PklModule

enum class Origin {
  FILE,
  STDLIB,
  HTTPS,
  PACKAGE,
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

  companion object {
    fun fromUri(uri: URI, project: Project): VirtualFile? {
      val logger = project.getLogger(this::class)
      return if (uri.scheme.equals("file", ignoreCase = true)) {
        FsFile(File(uri), project)
      } else if (uri.scheme.equals("pkl", ignoreCase = true)) {
        val origin = Origin.fromString(uri.authority.uppercase())
        if (origin == null) {
          logger.error("Invalid origin for pkl url: ${uri.authority}")
          return null
        }
        val path = uri.path.drop(1)
        when (origin) {
          Origin.FILE -> FsFile(File(path), project)
          Origin.STDLIB -> StdlibFile(path.replace(".pkl", ""), project)
          Origin.HTTPS -> HttpsFile(URI.create(path), project)
          else -> {
            logger.error("Origin $origin is not supported")
            null
          }
        }
      } else null
    }
  }
}

class FsFile(private val file: File, override val project: Project) : VirtualFile {

  override val name: String = file.name
  override val uri: URI = file.toURI()
  override val pklAuthority: String = Origin.FILE.name.lowercase()

  override fun parent(): VirtualFile? = file.parentFile?.let { FsFile(it, project) }

  override fun resolve(path: String): VirtualFile {
    return FsFile(file.resolve(path), project)
  }

  override fun toModule(): PklModule? {
    // get this module from the cache if possible so changes to it are propagated even
    // if the file was not saved
    return Builder.findModuleInCache(uri) ?: Builder.fileToModule(file, this)
  }
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
    return project.cacheManager.findHttpModule(uri)
  }
}

class JarFile
private constructor(
  private val originalFile: File,
  private val jarFile: java.util.jar.JarFile,
  private val entryPath: String,
  override val project: Project,
) : VirtualFile {

  override val name: String = entryPath.substringAfterLast('/')
  override val uri: URI = URI("jar://${originalFile.absolutePath}!/$entryPath")
  override val pklAuthority: String = Origin.MODULEPATH.name.lowercase()

  override fun parent(): VirtualFile? {
    if (entryPath == "/") return null

    val path =
      if (entryPath.contains('/')) {
        entryPath.substringBeforeLast('/')
      } else "/"
    return JarFile(originalFile, jarFile, path, project)
  }

  override fun resolve(path: String): VirtualFile {
    val actualPath =
      if (entryPath.contains('/') && entryPath != "/") {
        entryPath.substringBeforeLast('/') + "/" + path
      } else path
    return JarFile(originalFile, jarFile, actualPath, project)
  }

  override fun toModule(): PklModule? {
    val entry = jarFile.getJarEntry(entryPath) ?: return null
    if (entry.isDirectory) return null
    jarFile.getInputStream(entry).use { input ->
      val contents = input.bufferedReader().readText()
      return Builder.fileToModule(contents, uri, this)
    }
  }

  companion object {
    fun create(jarFile: File, entryPath: String, project: Project): JarFile? {
      try {
        if (!jarFile.exists()) return null
        val jar = java.util.jar.JarFile(jarFile)
        return JarFile(jarFile, jar, entryPath, project)
      } catch (_: IOException) {
        return null
      }
    }
  }
}
