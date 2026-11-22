/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.vladsch.flexmark.ast.LinkRef
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.NodeVisitor
import com.vladsch.flexmark.util.ast.VisitHandler
import com.vladsch.flexmark.util.sequence.BasedSequence
import io.github.treesitter.jtreesitter.Node
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.Project
import org.pkl.lsp.memberLinkKeywords
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.DocCommentResolvers
import org.pkl.lsp.util.CachedValue

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

  override val identifierNames: List<String> by lazy {
    identifiers.map { node ->
      val text = node.text
      if (text.startsWith("`")) text.substring(1, text.length - 1) else text
    }
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

class PklShebangCommentImpl(project: Project, parent: PklNode, override val ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklShebangComment {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitShebangComment(this)
  }
}

class PklDocCommentImpl(project: Project, parent: PklNode, override val ctx: Node) :
  AbstractPklNode(project, parent, ctx), PklDocComment {
  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitDocComment(this)
  }

  override val contents: String
    get() {
      return buildString {
        var isFirst = true
        for (line in text.lines()) {
          if (line.isBlank()) continue
          if (isFirst) {
            isFirst = false
          } else {
            append('\n')
          }
          val trimmed = line.trimStart()
          if (!trimmed.startsWith("///")) {
            continue
          }
          val trimLength = if (trimmed.length > 3 && trimmed[3].isWhitespace()) 4 else 3
          append(trimmed.substring(trimLength))
        }
      }
    }

  override val processedContents: String
    get() {
      var myContents = contents
      // replace these spans starting from the end so we don't need to track how replacements affect
      // offsets
      for (link in memberLinks.sortedByDescending { it.offsetWithinContents }) {
        val replacement =
          when (
            val resolved = DocCommentResolvers.resolveLink(project, link.reference, parent!!, true)
          ) {
            is PklNode -> {
              buildString {
                append("[`${link.text}`](")
                append(resolved.getLocationUri(forDocs = true))
                append(")")
              }
            }

            else -> {
              "`${link.reference}`"
            }
          }
        myContents =
          myContents.replaceRange(
            link.offsetWithinContents,
            link.offsetWithinContents + link.length,
            replacement,
          )
      }
      return myContents
    }

  private val lock = Any()

  private val markdownParser: Parser = Parser.builder().build()

  private val parsedMarkdown by lazy { markdownParser.parse(contents) }

  override val memberLinks: List<PklDocCommentMemberLink>
    get() {
      return project.cachedValuesManager.getCachedValue(this, "memberLinks", lock) {
        val links = buildList {
          val visitor =
            NodeVisitor(
              VisitHandler(LinkRef::class.java) { ref ->
                add(
                  PklDocCommentMemberLink(
                    getSpan(ref.reference),
                    if (ref.text.isEmpty()) ref.reference.toString() else ref.text.toString(),
                    ref.reference.toString(),
                    ref.startOffset,
                    ref.endOffset - ref.startOffset,
                  )
                )
              }
            )
          visitor.visit(parsedMarkdown)
        }
        CachedValue(links, containingFile)
      }!!
    }

  override val references: List<PklDocCommentReference>
    get() {
      val self = this
      return project.cachedValuesManager.getCachedValue(this, "references", lock) {
        val references = buildList {
          for (memberLink in memberLinks) {
            val line = memberLink.span.beginLine
            var offset = memberLink.span.beginCol
            val linkText = StringBuilder()
            val segments = memberLink.reference.split('.')
            for (segment in segments) {
              if (linkText.isNotEmpty()) {
                linkText.append('.')
              }
              linkText.append(segment)
              val mySpan = Span(line, offset, line, offset + segment.length)
              add(
                PklDocCommentReferenceImpl(
                  self,
                  span = mySpan,
                  fullSpan = memberLink.span,
                  link = linkText.toString(),
                  text = segment,
                  fullText = memberLink.reference,
                )
              )
              offset += segment.length + 1
            }
          }
        }
        CachedValue(references, containingFile)
      } ?: emptyList()
    }

  // `refNode`'s offsets are relative to `contents`, which strips leading indentation and the `///`
  // (and a following space, if any) from every line of `text`, and turns lines that don't start
  // with `///` into empty lines. Walk `text` mirroring that logic to find the real line/column of
  // the range.
  private fun getSpan(refNode: BasedSequence): Span {
    val startOffset = refNode.startOffset
    val endOffset = refNode.endOffset
    var line = span.beginLine
    // `text` starts at the first `///`, so the first line is offset by this node's start column.
    var col = span.beginCol
    var contentsOffset = 0
    for (rawLine in text.lines()) {
      val trimmed = rawLine.trimStart()
      if (trimmed.startsWith("///")) {
        val indent = rawLine.length - trimmed.length
        val prefixLength = if (trimmed.length > 3 && trimmed[3].isWhitespace()) 4 else 3
        val strippedLength = trimmed.length - prefixLength
        val startInLine = startOffset - contentsOffset
        if (startInLine in 0..strippedLength) {
          val startCol = col + indent + prefixLength + startInLine
          val endCol = col + indent + prefixLength + (endOffset - contentsOffset)
          return Span(line, startCol, line, endCol)
        }
        contentsOffset += strippedLength
      }
      contentsOffset += 1 // newline
      line++
      col = 1
    }
    throw IllegalStateException("Reference node offset is out of bounds of doc comment contents")
  }
}

class PklDocCommentReferenceImpl(
  val docComment: PklDocComment,
  override val span: Span,
  override val fullSpan: Span,
  override val link: String,
  override val text: String,
  override val fullText: String,
) : PklDocCommentReference {

  override fun resolve(context: PklProject?): PklNode? {
    if (fullText in memberLinkKeywords && fullText != "module" && fullText != "this") return null
    return DocCommentResolvers.resolveLink(docComment.project, link, docComment.parent!!, true)
  }
}
