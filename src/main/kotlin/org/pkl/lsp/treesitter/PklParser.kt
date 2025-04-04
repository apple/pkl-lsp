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
package org.pkl.lsp.treesitter

import io.github.treesitter.jtreesitter.InputEncoding
import io.github.treesitter.jtreesitter.Language
import io.github.treesitter.jtreesitter.Parser
import io.github.treesitter.jtreesitter.Tree
import org.pkl.lsp.Component
import org.pkl.lsp.Project

/** A Pkl parser using tree-sitter-pkl */
class PklParser(project: Project) : Component(project) {
  fun parse(text: String, oldAst: Tree? = null): Tree {
    return parser().use { parser ->
      parser.parse(text, InputEncoding.UTF_8, oldAst).orElseThrow(::parsingHalted)
    }
  }

  private fun parser(): Parser = Parser(Language(TreeSitterPkl.language()))

  private fun parsingHalted() = RuntimeException("Parsing was halted")
}
