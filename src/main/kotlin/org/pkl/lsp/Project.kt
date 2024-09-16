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

import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType
import org.pkl.lsp.services.*
import org.pkl.lsp.util.CachedValuesManager

class Project(private val server: PklLSPServer) {
  val stdlib: Stdlib by lazy { Stdlib(this) }

  val pklBaseModule: PklBaseModule by lazy { PklBaseModule(this) }

  val packageManager: PackageManager by lazy { PackageManager(this) }

  val pklProjectManager: PklProjectManager by lazy { PklProjectManager(this) }

  val cachedValuesManager: CachedValuesManager by lazy { CachedValuesManager(this) }

  val pklCli: PklCli by lazy { PklCli(this) }

  val settingsManager: SettingsManager by lazy { SettingsManager(this) }

  val messageBus: MessageBus by lazy { MessageBus(this) }

  val virtualFileManager: VirtualFileManager by lazy { VirtualFileManager(this) }

  val languageClient: PklLanguageClient by lazy { server.client() }

  val pklFileTracker: PklFileTracker by lazy { PklFileTracker(this) }

  val diagnosticsManager: DiagnosticsManager by lazy { DiagnosticsManager(this) }

  fun initialize(): CompletableFuture<*> {
    return CompletableFuture.allOf(*myComponents.map { it.initialize() }.toTypedArray())
  }

  fun dispose() {
    myComponents.forEach { it.dispose() }
  }

  /** Creates a logger with the given class as the logger's name. */
  fun getLogger(clazz: KClass<*>): ClientLogger =
    ClientLogger(
      server.client(),
      server.verbose,
      clazz.qualifiedName ?: clazz.java.descriptorString(),
    )

  private val myComponents: Iterable<Component>
    get() {
      return this::class
        .members
        .filterIsInstance(KProperty::class.java)
        .filter { it.returnType.isSubtypeOf(Component::class.starProjectedType) }
        .map { it.call(this) as Component }
    }
}
