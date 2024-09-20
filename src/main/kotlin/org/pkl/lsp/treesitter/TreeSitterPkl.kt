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
package org.pkl.lsp.treesitter

import java.lang.foreign.*
import org.pkl.lsp.treesitter.NativeLibraries.treeSitterPkl

class TreeSitterPkl {
  companion object {
    @JvmStatic
    private val VOID_PTR: ValueLayout =
      ValueLayout.ADDRESS.withTargetLayout(
        MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE)
      )
    @JvmStatic private val FUNC_DESC: FunctionDescriptor = FunctionDescriptor.of(VOID_PTR)
    @JvmStatic private val LINKER: Linker = Linker.nativeLinker()
    @JvmStatic private val INSTANCE: TreeSitterPkl = TreeSitterPkl()

    @JvmStatic
    fun language(): MemorySegment {
      return INSTANCE.call("tree_sitter_pkl")
    }

    private fun unresolved(name: String): UnsatisfiedLinkError {
      return UnsatisfiedLinkError("Unresolved symbol: $name")
    }
  }

  private val arena: Arena = Arena.ofAuto()

  private val symbols: SymbolLookup
    get() =
      SymbolLookup.libraryLookup(treeSitterPkl.libraryPath, arena).or(SymbolLookup.loaderLookup())

  @Suppress("SameParameterValue")
  private fun call(name: String): MemorySegment {
    val address = symbols.find(name).orElseThrow { unresolved(name) }
    try {
      val function = LINKER.downcallHandle(address, FUNC_DESC)
      return (function.invokeExact() as MemorySegment).asReadOnly()
    } catch (e: Throwable) {
      throw RuntimeException("Call to $name failed", e)
    }
  }
}
