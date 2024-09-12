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
package org.pkl.lsp.services

import java.util.concurrent.ConcurrentHashMap
import org.pkl.lsp.Component
import org.pkl.lsp.Project

data class Topic<T : Any>(val name: String)

class MessageBus(project: Project) : Component(project) {
  private val observers: MutableMap<Topic<*>, MutableList<(Any) -> Unit>> = ConcurrentHashMap()

  /** Subscribe to events fired on [topic]. */
  fun <T : Any> subscribe(topic: Topic<T>, observer: (T) -> Unit) =
    synchronized(this) {
      val list = observers.computeIfAbsent(topic) { ArrayList() }
      @Suppress("UNCHECKED_CAST") list.add(observer as (Any) -> Unit)
    }

  /** Emit an event on [topic]. */
  fun <T : Any> emit(topic: Topic<T>, event: T) =
    synchronized(this) {
      val observers = observers[topic] ?: return
      for (observer in observers) {
        observer(event)
      }
    }

  override fun dispose() = synchronized(this) { observers.clear() }
}
