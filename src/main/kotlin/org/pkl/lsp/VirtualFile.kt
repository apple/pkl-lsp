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
import java.util.concurrent.CompletableFuture
import javax.naming.OperationNotSupportedException
import kotlin.io.path.*
import org.pkl.core.module.ModuleKeyFactories.file
import org.pkl.core.parser.LexParseException
import org.pkl.core.util.IoUtils
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklModuleImpl
import org.pkl.lsp.packages.PackageDependency
import org.pkl.lsp.packages.dto.PackageMetadata
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.services.PklProjectManager.Companion.PKL_PROJECT_FILENAME
import org.pkl.lsp.treesitter.PklParser
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
  var version: Long?

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

  /** Tells if this file is a directory. */
  val isDirectory: Boolean

  /** Returns the children of this directory, or `null` if this is not a directory. */
  val children: List<VirtualFile>?

  var contents: String

  fun parent(): VirtualFile?

  fun resolve(path: String): VirtualFile?

  fun getModule(): CompletableFuture<PklModule?>
}

sealed class BaseFile : VirtualFile {
  override fun getModificationCount(): Long = version ?: -1L

  override var version: Long? = null

  abstract fun doReadContents(): String

  private var readError: Exception? = null

  // If contents have not yet been set, read contents from external source, possibly performing I/O.
  override var contents: String
    get() {
      return myContents ?: doReadContents()
    }
    set(text) {
      myContents = text
    }

  @Synchronized
  final override fun getModule(): CompletableFuture<PklModule?> {
    if (isDirectory) {
      return CompletableFuture.completedFuture(null)
    }
    if (readError != null) {
      readError = null
      project.cachedValuesManager.clearCachedValue(cacheKey)
    }
    return project.cachedValuesManager.getCachedValue(cacheKey) {
      CachedValue(CompletableFuture.supplyAsync(::doBuildModule), this)
    }!!
  }

  protected val logger by lazy { project.getLogger(this::class) }

  private val cacheKey
    get() = "VirtualFile($uri)"

  private var myContents: String? = null

  private val parser = PklParser()

  private fun doBuildModule(): PklModule? {
    return try {
      logger.log("building $uri")
      val moduleCtx = parser.parse(contents, project.astExecutor)
      if (readError != null) {
        readError = null
      }
      return PklModuleImpl(moduleCtx, this)
    } catch (e: LexParseException) {
      logger.warn("Parser Error building $file: ${e.message}")
      null
    } catch (e: Exception) {
      logger.warn("Error building $file: ${e.message} ${e.stackTraceToString()}")
      readError = e
      null
    }
  }
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

  override val isDirectory: Boolean
    get() = path.isDirectory()

  override val children: List<VirtualFile>?
    get() =
      if (!isDirectory) null
      else path.listDirectoryEntries().mapNotNull { project.virtualFileManager.get(it) }

  override fun parent(): VirtualFile? = path.parent?.let { project.virtualFileManager.get(it) }

  override fun resolve(path: String): VirtualFile? {
    val resolvedPath = this.path.resolve(path)
    return if (Files.exists(resolvedPath)) project.virtualFileManager.get(resolvedPath.toUri())
    else null
  }

  override fun doReadContents(): String = path.readText()
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

  override val isDirectory: Boolean
    get() = false

  override val children: List<VirtualFile>?
    get() = null

  override fun parent(): VirtualFile? = null

  override fun resolve(path: String): VirtualFile? = null

  override fun doReadContents(): String {
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

  override val isDirectory: Boolean = false

  override val children: List<VirtualFile>? = null

  override fun parent(): VirtualFile? {
    val newUri = if (uri.path.endsWith("/")) uri.resolve("..") else uri.resolve(".")
    return project.virtualFileManager.get(newUri)
  }

  override fun resolve(path: String): VirtualFile? {
    return project.virtualFileManager.get(uri.resolve(path))
  }

  override fun doReadContents(): String {
    return _doReadContents().get()
  }

  private val _doReadContents = debounce {
    CompletableFuture.supplyAsync {
      logger.log("Fetching $uri")
      uri.toURL().readText()
    }
  }
}

class JarFile(override val path: Path, override val uri: URI, override val project: Project) :
  BaseFile() {
  override val name: String
    get() = path.name

  override val pklProject: PklProject? = null
  override val pklProjectDir: VirtualFile? = null

  override val isDirectory: Boolean
    get() = path.isDirectory()

  override val children: List<VirtualFile>?
    get() =
      if (!isDirectory) null
      else path.listDirectoryEntries().mapNotNull { project.virtualFileManager.get(it) }

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

  override fun parent(): VirtualFile? = path.parent?.let { project.virtualFileManager.get(it) }

  override fun resolve(path: String): VirtualFile? =
    project.virtualFileManager.get(this.path.resolve(path))

  override fun doReadContents(): String =
    project.cachedValuesManager.getCachedValue("${javaClass.simpleName}-contents-${uri}") {
      CachedValue(path.readText())
    }!!
}

class EphemeralFile(private val text: String, override val project: Project) : BaseFile() {
  override val name: String = "ephemeral"
  override val uri: URI = URI("fake:fake")
  override val pklAuthority: String = "fake"
  override val path: Path
    get() = throw OperationNotSupportedException()

  override val isDirectory: Boolean = false
  override val children: List<VirtualFile>? = null
  override val pklProject: PklProject? = null
  override val pklProjectDir: VirtualFile? = null
  override val `package`: PackageDependency? = null

  override fun parent(): VirtualFile? = null

  override fun resolve(path: String): VirtualFile? = null

  override fun doReadContents(): String = text
}
