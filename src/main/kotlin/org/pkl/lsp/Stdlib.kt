/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.pkl.lsp.packages.dto.Version

class Stdlib(project: Project) : Component(project) {

  lateinit var myFiles: Map<String, VirtualFile>
  lateinit var myVersion: Version
  private var initializedFiles: Boolean = false
  private var initilizedVersion: Boolean = false
  private val workspaceFolders: MutableSet<Path> = mutableSetOf()
  private var workspaceFoldersDirty: Boolean = false

  var resolvedWorkspaceFolder: Path? = null
    private set

  val files: Map<String, VirtualFile>
    get() = loadFiles()

  val base: VirtualFile
    get() = files["base"]!!

  val version: Version
    get() = loadVersion()

  fun initialize(folders: List<Path>?) {
    project.messageBus.subscribe(workspaceFolderTopic, ::handleWorkspaceFolderEvent)
    workspaceFolders.clear()
    initializedFiles = false
    initilizedVersion = false
    if (folders != null) {
      workspaceFolders.addAll(folders)
    }
  }

  private fun loadFiles(): Map<String, VirtualFile> =
    synchronized(this) {
      val workspaceWithStdlib by lazy {
        for (folder in workspaceFolders) {
          if (project.virtualFileManager.get(folder.resolve("stdlib/base.pkl")) != null) {
            resolvedWorkspaceFolder = folder
            return@lazy resolvedWorkspaceFolder
          }
        }
        workspaceFoldersDirty = false
        return@lazy null
      }

      if (
        initializedFiles &&
          (!workspaceFoldersDirty || (workspaceWithStdlib == resolvedWorkspaceFolder))
      ) {
        return myFiles
      }

      val stdlibFolder =
        workspaceWithStdlib?.resolve("stdlib")
          ?: run {
            val baseModuleUri1 =
              this@Stdlib.javaClass.getResource("/org/pkl/stdlib/base.pkl")!!.toURI()
            ensureJarFileSystem(baseModuleUri1)
            Path.of(baseModuleUri1).parent
          }
      myFiles =
        stdlibFolder
          .listDirectoryEntries()
          .filter { it.extension == "pkl" && it.name != "doc-package-info.pkl" }
          .associate { file ->
            val name = file.name.replace(".pkl", "")
            logger.log("Found stdlib file: pkl:$name")
            name to project.virtualFileManager.get(URI("pkl:$name"))!!
          }

      initilizedVersion = false
      initializedFiles = true
      return myFiles
    }

  private fun loadVersion(): Version =
    synchronized(this) {
      if (initilizedVersion) return myVersion

      val baseModule = files["base"]!!.getModule().get()!!
      // The base module should always have a minPklVersion, otherwise it's a bug
      myVersion =
        baseModule.minPklVersion
          ?: throw PklLspBugException("Pkl base module does not have a minimum Pkl version")

      return myVersion
    }

  private fun handleWorkspaceFolderEvent(event: WorkspaceFoldersChangeEvent) {
    synchronized(this) {
      for (workspaceFolder in event.added) {
        val path = Path.of(URI(workspaceFolder.uri))
        workspaceFolders.add(path)
      }
      for (workspaceFolder in event.removed) {
        val path = Path.of(URI(workspaceFolder.uri))
        workspaceFolders.removeIf { it.toUri() == path.toUri() }
      }
      workspaceFoldersDirty = true
    }
    loadFiles()
  }
}
