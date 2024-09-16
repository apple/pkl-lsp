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
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.WorkspaceService
import org.pkl.lsp.services.Topic

val workspaceTopic = Topic<WorkspaceEvent>("WorkspaceEvent")

sealed interface WorkspaceEvent {
  val files: List<URI>

  data class Created(override val files: List<URI>) : WorkspaceEvent

  data class Deleted(override val files: List<URI>) : WorkspaceEvent
}

val workspaceConfigurationChangedTopic =
  Topic<WorkspaceConfigurationChangedEvent>("WorkspaceConfigurationChanged")

object WorkspaceConfigurationChangedEvent

val workspaceFolderTopic = Topic<WorkspaceFoldersChangeEvent>("WorkspaceFolderEvent")

class PklWorkspaceService(private val project: Project) : WorkspaceService {
  override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
    project.messageBus.emit(workspaceConfigurationChangedTopic, WorkspaceConfigurationChangedEvent)
  }

  override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
    project.messageBus.emit(workspaceFolderTopic, params.event)
  }

  override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams?) {}

  override fun didCreateFiles(params: CreateFilesParams) {
    val files = params.files.map { URI(it.uri) }
    project.messageBus.emit(workspaceTopic, WorkspaceEvent.Created(files))
  }

  override fun didDeleteFiles(params: DeleteFilesParams) {
    val files = params.files.map { URI(it.uri) }
    project.messageBus.emit(workspaceTopic, WorkspaceEvent.Deleted(files))
  }
}
