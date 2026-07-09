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
      val doc = text.trim()
      return doc.lines().joinToString("\n") {
        it.substring(if (it.length > 3 && it[3].isWhitespace()) 4 else 3)
      }
    }

  override val processedContents: String
    get() {
      var myContents = contents
      // replace these spans starting from the end so we don't need to track how replacements affect
      // offsets
      for (link in
        memberLinks.sortedWith(
          Comparator.comparing<PklDocCommentMemberLink, Int> { -it.span.beginCol }
            .thenComparing { -it.span.beginLine }
        )) {
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

  // `refNode`'s offsets are relative to `contents`, which strips the leading `///` (and a
  // following space, if any) from every line of `text`. Walk `text` to find the real
  // line/column of that range, mirroring the same prefix-stripping `contents` does so the
  // offsets stay in sync.
  private fun getSpan(refNode: BasedSequence): Span {
    val doc = text.trim()
    var line = span.beginLine
    var col = span.beginCol
    for (i in 0 until text.length - text.trimStart().length) {
      if (text[i] == '\n') {
        line++
        col = 1
      } else {
        col++
      }
    }

    val startOffset = refNode.startOffset
    val endOffset = refNode.endOffset
    var contentsOffset = 0
    for (rawLine in doc.lines()) {
      val prefixLength = if (rawLine.length > 3 && rawLine[3].isWhitespace()) 4 else 3
      val strippedLength = rawLine.length - prefixLength
      val startInLine = startOffset - contentsOffset
      if (startInLine in 0..strippedLength) {
        val startCol = col + prefixLength + startInLine
        val endCol = col + prefixLength + (endOffset - contentsOffset)
        return Span(line, startCol, line, endCol)
      }
      contentsOffset += strippedLength + 1
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
