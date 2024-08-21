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

import kotlin.reflect.KClass

class Project(private val server: PklLSPServer) {
  val cacheManager: CacheManager by lazy { CacheManager(this) }

  val pklBaseModule: PklBaseModule by lazy { PklBaseModule(this) }

  val stdlib: Stdlib by lazy { Stdlib(this) }

  /** Creates a logger with the given class as the logger's name. */
  fun getLogger(clazz: KClass<*>): ClientLogger =
    ClientLogger(
      server.client(),
      server.verbose,
      clazz.qualifiedName ?: clazz.java.descriptorString(),
    )
}