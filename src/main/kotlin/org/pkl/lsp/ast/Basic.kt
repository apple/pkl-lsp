/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.Project

class PklQualifiedIdentifierImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklQualifiedIdentifier {
  override val identifiers: List<Terminal> by lazy {
    terminals.filter { it.type == TokenType.Identifier }
  }
  override val fullName: String by lazy { text }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitQualifiedIdentifier(this)
  }
}

class PklStringConstantImpl(
  override val project: Project,
  override val parent: PklNode,
  override val ctx: Node,
) : AbstractPklNode(project, parent, ctx), PklStringConstant {
  override val value: String by lazy { text }

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitStringConstant(this)
  }
}

class PklLineCommentImpl(project: Project, parent: PklNode, override val ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklLineComment {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitLineComment(this)
  }
}

class PklBlockCommentImpl(project: Project, parent: PklNode, override val ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklBlockComment {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitBlockComment(this)
  }
}
