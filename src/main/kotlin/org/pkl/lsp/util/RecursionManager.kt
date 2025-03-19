/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.util

import java.util.Collections
import java.util.IdentityHashMap

object RecursionManager {

  private val recursionState: ThreadLocal<MutableMap<String, MutableSet<Any>>> =
    ThreadLocal.withInitial(::mutableMapOf)

  fun <T> doPreventingRecursion(key: Any, methodName: String, default: T, compute: () -> T): T {
    val myState = recursionState.get()
    val seenKeys =
      myState[methodName]
        ?: Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { state ->
          myState[methodName] = state
        }
    if (seenKeys.contains(key)) {
      return default
    }
    seenKeys.add(key)
    try {
      return compute().also {
        seenKeys.remove(key)
        if (seenKeys.isEmpty()) {
          myState.remove(methodName)
        }
      }
    } catch (e: Throwable) {
      // prevent memory leak; clean up state on any exception
      myState.remove(methodName)
      throw e
    }
  }
}
