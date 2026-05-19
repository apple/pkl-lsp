/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import java.nio.file.Path
import kotlin.io.path.createFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pkl.lsp.LspTestBase

class ModulepathResolverTest : LspTestBase() {
  @Test
  fun listChildren(@TempDir modulepath1: Path, @TempDir modulepath2: Path) {
    fakeProject.settingsManager.update { settings ->
      settings.copy(modulepath = listOf(modulepath1, modulepath2))
    }
    val foo1 = modulepath1.resolve("foo.pkl").createFile()
    val bar1 = modulepath1.resolve("bar.pkl").createFile()
    modulepath2.resolve("foo.pkl").createFile() // this is shadowed by the earlier `foo.pkl`
    val qaz = modulepath2.resolve("qaz.pkl").createFile()

    val children =
      fakeProject.modulepathResolver.listChildren(fakeProject.virtualFileManager.get(modulepath1)!!)
    assert(children.size == 3)
    assert(children[0].path == bar1)
    assert(children[1].path == foo1)
    assert(children[2].path == qaz)
  }
}
