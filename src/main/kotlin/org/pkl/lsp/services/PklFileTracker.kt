/*
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
package org.pkl.lsp.services

import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import org.pkl.lsp.Component
import org.pkl.lsp.Project
import org.pkl.lsp.WorkspaceEvent
import org.pkl.lsp.util.ModificationTracker
import org.pkl.lsp.workspaceTopic

class PklFileTracker(project: Project, private val filter: ((WorkspaceEvent) -> Boolean)? = null) :
  Component(project), ModificationTracker {
  private var modificationCount: AtomicLong = AtomicLong(0)

  private var myTrackers: MutableList<PklFileTracker> =
    Collections.synchronizedList(mutableListOf())

  override fun initialize(): CompletableFuture<*> {
    project.messageBus.subscribe(workspaceTopic) { event ->
      if (filter != null && !filter.invoke(event)) {
        return@subscribe
      }
      modificationCount.incrementAndGet()
    }
    return CompletableFuture.completedFuture(Unit)
  }

  override fun dispose(): Unit =
    synchronized(myTrackers) {
      for (tracker in myTrackers) {
        tracker.dispose()
      }
      myTrackers.clear()
    }

  fun filter(filter: (WorkspaceEvent) -> Boolean): PklFileTracker {
    val tracker = PklFileTracker(project, filter)
    myTrackers.add(tracker)
    tracker.initialize()
    return tracker
  }

  override fun getModificationCount(): Long = modificationCount.get()
}
