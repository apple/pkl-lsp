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
package org.pkl.lsp.util

import java.util.concurrent.ConcurrentHashMap
import org.pkl.lsp.Component
import org.pkl.lsp.Project

data class CachedValue<T>(val value: T, val dependencies: List<ModificationTracker>) {
  constructor(value: T) : this(value, listOf())

  constructor(
    value: T,
    vararg dependencies: ModificationTracker,
  ) : this(value, dependencies.toList())
}

class CachedValuesManager(project: Project) : Component(project) {
  private val cachedValues: MutableMap<String, Pair<List<Long>, CachedValue<*>>> =
    ConcurrentHashMap()

  /** Returns the currently cached value, or `null` if the cached value is out of date. */
  @Suppress("UNCHECKED_CAST")
  private fun <T> getValue(key: String): CachedValue<T>? {
    val (lastModificationCounts, cachedValue) = cachedValues[key] ?: return null
    if (cachedValue.isUpToDate(lastModificationCounts)) {
      return cachedValue as CachedValue<T>
    }
    return null
  }

  private fun storeCachedValue(key: String, value: CachedValue<*>) {
    cachedValues[key] = value.dependencies.map { it.getModificationCount() } to value
  }

  private fun CachedValue<*>.isUpToDate(lastModificationCounts: List<Long>): Boolean {
    if (lastModificationCounts.size != dependencies.size) {
      throw IllegalArgumentException("Modification counts do not match dependency size")
    }
    for ((idx, count) in lastModificationCounts.withIndex()) {
      if (dependencies[idx].getModificationCount() != count) {
        return false
      }
    }
    return true
  }

  fun <T> getCachedValue(key: String, provider: () -> CachedValue<T>?): T? {
    return getValue<T>(key)?.value ?: provider()?.also { storeCachedValue(key, it) }?.value
  }
}
