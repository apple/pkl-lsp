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
package org.pkl.lsp.ast

import io.github.treesitter.jtreesitter.Node
import io.github.treesitter.jtreesitter.Range
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.jvm.optionals.getOrNull

/** Wraps a tree sitter node so all the access comes from the same thread */
class TreeSitterNode(private val ctx: Node, private val executor: ExecutorService) : AutoCloseable {
  val text: String by lazy { call { ctx.utfAwareText() } }

  val type: String by lazy { call { ctx.type } }

  val isMissing: Boolean by lazy { call { ctx.isMissing } }

  val isExtra: Boolean by lazy { call { ctx.isExtra } }

  val id: Long by lazy { call { ctx.id } }

  val range: Range by lazy { call { ctx.range } }

  val source: String by lazy { call { ctx.tree.text } }

  val children: List<TreeSitterNode> by lazy {
    call { ctx.children.map { TreeSitterNode(it, executor) } }
  }

  val childCount: Int by lazy { call { ctx.childCount } }

  fun getChildByFieldName(name: String): TreeSitterNode? {
    return call { ctx.getChildByFieldName(name).getOrNull()?.let { TreeSitterNode(it, executor) } }
  }

  override fun hashCode(): Int {
    return call { ctx.hashCode() }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TreeSitterNode) return false
    return call { ctx == other.ctx }
  }

  override fun close() {
    call { ctx.tree.close() }
  }

  private inline fun <T> call(crossinline fn: () -> T): T {
    return executor.submit(Callable { fn() }).get()
  }
}
