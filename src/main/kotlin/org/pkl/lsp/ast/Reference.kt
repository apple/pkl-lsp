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
package org.pkl.lsp.ast

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.Project

interface PklReferenceQualifiedAccessProxy : PklNode {
  val name: String
  val referent: PklType
  val type: PklType

  fun toCompletionItem(): CompletionItem {
    return CompletionItem(name).apply {
      kind = CompletionItemKind.Field
      detail = type.render()
    }
  }
}

class PklReferenceQualifiedAccessProxyImpl(
  project: Project,
  override val name: String,
  override val referent: PklType,
) : FakePklNode(project), PklReferenceQualifiedAccessProxy {
  override fun <R> accept(visitor: PklVisitor<R>): R? = null

  override val type: PklType =
    object : FakePklNode(project), PklDeclaredType {
      override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitDeclaredType(this)

      override val name: PklTypeName =
        object : FakePklNode(project), PklTypeName {
          override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTypeName(this)

          override val text: String = "Reference"

          override val moduleName: PklModuleName? = null
          override val simpleTypeName: PklSimpleTypeName =
            object : FakePklNode(project), PklSimpleTypeName {
              override fun <R> accept(visitor: PklVisitor<R>): R? =
                visitor.visitSimpleTypeName(this)

              override val identifier: Terminal =
                object : FakePklNode(project), Terminal {
                  override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTerminal(this)

                  override val text: String = "Reference"
                  override val type: TokenType = TokenType.Identifier
                }
            }
        }
      override val typeArgumentList: PklTypeArgumentList =
        object : FakePklNode(project), PklTypeArgumentList {
          override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTypeArgumentList(this)

          override val types: List<PklType> = listOf(referent)
        }
    }
}
