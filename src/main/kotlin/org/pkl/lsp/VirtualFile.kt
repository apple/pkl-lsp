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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.naming.OperationNotSupportedException
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import org.pkl.core.parser.Parser
import org.pkl.core.util.IoUtils
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklModuleImpl
import org.pkl.lsp.packages.PackageDependency
import org.pkl.lsp.packages.dto.PackageMetadata
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.services.PklProjectManager.Companion.PKL_PROJECT_FILENAME
import org.pkl.lsp.util.CachedValue
import org.pkl.lsp.util.ModificationTracker

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

interface VirtualFile : ModificationTracker {
  val name: String
  val uri: URI
  val pklAuthority: String
  val project: Project

  /**
   * The NIO path backing this file.
   *
   * May throw [OperationNotSupportedException] if this file cannot be converted to a [Path].
   */
  val path: Path

  /** The closest ancestor directory containing a PklProject file. */
  val pklProjectDir: VirtualFile?

  /** Tells if this file is in a directory that contains a PklProject file. */
  val pklProject: PklProject?

  /** Tells if this file is within a package. */
  val `package`: PackageDependency?

  fun parent(): VirtualFile?

  fun resolve(path: String): VirtualFile?

  fun toModule(): PklModule?

  fun contents(): String
}

sealed class BaseFile : VirtualFile {
  internal val modificationCount: AtomicLong = AtomicLong(0)

  override fun getModificationCount(): Long = modificationCount.get()
}

class FsFile(override val path: Path, override val project: Project) : BaseFile() {
  override val name: String = path.name
  override val uri: URI = path.toUri()
  override val pklAuthority: String = Origin.FILE.name.lowercase()

  override val pklProjectDir: VirtualFile?
    get() =
      project.cachedValuesManager.getCachedValue("FsFile.getProjectDir($path)") {
        val dependency = project.pklProjectManager.addedOrRemovedFilesModificationTracker
        var dir = if (Files.isDirectory(path)) this else parent()
        while (dir != null) {
          if (dir.resolve(PKL_PROJECT_FILENAME) != null) {
            return@getCachedValue CachedValue(dir, listOf(dependency))
          }
          dir = dir.parent()
        }
        CachedValue(null, listOf(dependency))
      }

  override val pklProject: PklProject?
    get() = pklProjectDir?.let { project.pklProjectManager.getPklProject(it) }

  override val `package`: PackageDependency? = null

  override fun parent(): VirtualFile? = path.parent?.let { project.virtualFileManager.get(it) }

  override fun resolve(path: String): VirtualFile? {
    val resolvedPath = this.path.resolve(path)
    return if (Files.exists(resolvedPath)) project.virtualFileManager.get(resolvedPath.toUri())
    else null
  }

  override fun toModule(): PklModule? {
    // get this module from the cache if possible so changes to it are propagated even
    // if the file was not saved
    val builder = project.builder
    return builder.findModuleInCache(uri) ?: builder.pathToModule(path, this)
  }

  override fun contents(): String = path.readText()
}

class StdlibFile(moduleName: String, override val project: Project) : BaseFile() {
  override val name: String = moduleName
  override val uri: URI = URI("pkl:$moduleName")
  override val pklAuthority: String = Origin.STDLIB.name.lowercase()
  override val path: Path
    get() = throw OperationNotSupportedException()

  override val pklProject: PklProject? = null
  override val pklProjectDir: VirtualFile? = null
  override val `package`: PackageDependency? = null

  override fun parent(): VirtualFile? = null

  override fun resolve(path: String): VirtualFile? = null

  override fun toModule(): PklModule? {
    return project.stdlib.getModule(name)
  }

  override fun contents(): String {
    return IoUtils.readClassPathResourceAsString(javaClass, "/org/pkl/core/stdlib/$name.pkl")
  }
}

class HttpsFile(override val uri: URI, override val project: Project) : BaseFile() {
  override val name: String = ""
  override val pklAuthority: String = Origin.HTTPS.name.lowercase()
  override val path: Path
    get() = throw OperationNotSupportedException()

  override val pklProject: PklProject? = null
  override val pklProjectDir: VirtualFile? = null
  override val `package`: PackageDependency? = null

  override fun parent(): VirtualFile? {
    val newUri = if (uri.path.endsWith("/")) uri.resolve("..") else uri.resolve(".")
    return project.virtualFileManager.get(newUri)
  }

  override fun resolve(path: String): VirtualFile? {
    return project.virtualFileManager.get(uri.resolve(path))
  }

  override fun toModule(): PklModule? {
    return project.fileCacheManager.findHttpModule(uri)
  }

  override fun contents(): String {
    return project.fileCacheManager.findHttpContent(uri) ?: ""
  }
}

class JarFile : BaseFile {
  override val path: Path
  override val uri: URI
  override val project: Project
  override val name: String
    get() = path.name

  override val pklProject: PklProject? = null
  override val pklProjectDir: VirtualFile? = null

  override val `package`: PackageDependency?
    get() =
      project.cachedValuesManager.getCachedValue("JarFile.package(${uri})") {
        val jarFile: Path = Path.of(URI(uri.toString().drop(4).substringBefore("!/")))
        val jsonFile =
          jarFile.parent.resolve(jarFile.nameWithoutExtension + ".json")
            ?: return@getCachedValue null
        if (!Files.exists(jsonFile)) return@getCachedValue null
        val metadata = PackageMetadata.load(jsonFile)
        val packageUri = metadata.packageUri
        CachedValue(packageUri.asPackageDependency(null))
      }

  override val pklAuthority: String
    get() = Origin.JAR.name.lowercase()

  constructor(path: Path, uri: URI, project: Project) {
    this.path = path
    this.uri = uri
    this.project = project
  }

  constructor(uri: URI, project: Project) {
    this.uri = uri
    this.path = Path.of(uri)
    this.project = project
  }

  override fun parent(): VirtualFile? = path.parent?.let { project.virtualFileManager.get(it) }

  override fun resolve(path: String): VirtualFile? =
    project.virtualFileManager.get(this.path.resolve(path))

  override fun toModule(): PklModule? {
    val builder = project.builder
    return builder.findModuleInCache(uri) ?: builder.pathToModule(path, this)
  }

  override fun contents(): String =
    project.cachedValuesManager.getCachedValue("${javaClass.simpleName}-contents-${uri}") {
      CachedValue(path.readText())
    }!!
}

class EphemeralFile(private val text: String, override val project: Project) : BaseFile() {
  companion object {
    private val parser = Parser()
  }

  override val name: String = "ephemeral"
  override val uri: URI = URI("fake:fake")
  override val pklAuthority: String = "fake"
  override val path: Path
    get() = throw OperationNotSupportedException()

  override val pklProject: PklProject? = null
  override val pklProjectDir: VirtualFile? = null
  override val `package`: PackageDependency? = null

  override fun parent(): VirtualFile? = null

  override fun resolve(path: String): VirtualFile? = null

  override fun toModule(): PklModule {
    val ctx = parser.parseModule(text)
    return PklModuleImpl(ctx, this)
  }

  override fun contents(): String = text
}
