package org.pkl.lsp.treesitter

import java.lang.foreign.SymbolLookup
import java.lang.foreign.Arena
import io.github.treesitter.jtreesitter.NativeLibraryLookup

class LibraryLookup : NativeLibraryLookup {
  override fun get(arena: Arena): SymbolLookup =
    SymbolLookup.libraryLookup(NativeLibraries.treeSitter.libraryPath, arena)
}