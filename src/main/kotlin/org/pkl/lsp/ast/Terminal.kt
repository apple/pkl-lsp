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
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.Project

class TerminalImpl(
  project: Project,
  override val parent: PklNode,
  override val ctx: Node,
  override val type: TokenType,
) : AbstractPklNode(project, parent, ctx), Terminal {

  override fun <R> accept(visitor: PklVisitor<R>): R? {
    return visitor.visitTerminal(this)
  }
}

val Terminal.isModifier: Boolean
  get() {
    return modifierTypes.contains(type)
  }

fun Node.toTerminal(parent: PklNode): Terminal? {
  val tokenType =
    when (type) {
      "abstract" -> TokenType.ABSTRACT
      "amends" -> TokenType.AMENDS
      "as" -> TokenType.AS
      "class" -> TokenType.CLASS
      "const" -> TokenType.CONST
      "else" -> TokenType.ELSE
      "extends" -> TokenType.EXTENDS
      "external" -> TokenType.EXTERNAL
      "false" -> TokenType.FALSE
      "fixed" -> TokenType.FIXED
      "for" -> TokenType.FOR
      "function" -> TokenType.FUNCTION
      "hidden" -> TokenType.HIDDEN
      "if" -> TokenType.IF
      "import" -> TokenType.IMPORT
      "import*" -> TokenType.IMPORT_GLOB
      "in" -> TokenType.IN
      "is" -> TokenType.IS
      "let" -> TokenType.LET
      "local" -> TokenType.LOCAL
      "module" -> TokenType.MODULE
      "new" -> TokenType.NEW
      "nothing" -> TokenType.NOTHING
      "null" -> TokenType.NULL
      "open" -> TokenType.OPEN
      "out" -> TokenType.OUT
      "outer" -> TokenType.OUTER
      "read" -> TokenType.READ
      "read*" -> TokenType.READ_GLOB
      "read?" -> TokenType.READ_OR_NULL
      "super" -> TokenType.SUPER
      "this" -> TokenType.THIS
      "throw" -> TokenType.THROW
      "trace" -> TokenType.TRACE
      "true" -> TokenType.TRUE
      "typealias" -> TokenType.TYPE_ALIAS
      "unknown" -> TokenType.UNKNOWN
      "when" -> TokenType.WHEN
      // TODO: these terminal do not exist in tree-sitter
      //      PklParser.PROTECTED -> TokenType.PROTECTED
      //      PklParser.OVERRIDE -> TokenType.OVERRIDE
      //      PklParser.RECORD -> TokenType.RECORD
      //      PklParser.DELETE -> TokenType.DELETE
      //      PklParser.CASE -> TokenType.CASE
      //      PklParser.SWITCH -> TokenType.SWITCH
      //      PklParser.VARARG -> TokenType.VARARG
      "(" -> TokenType.LPAREN
      ")" -> TokenType.RPAREN
      "{" -> TokenType.LBRACE
      "}" -> TokenType.RBRACE
      "[" -> TokenType.LBRACK
      "]" -> TokenType.RBRACK
      "[[" -> TokenType.LPRED
      "]]" -> TokenType.RPRED
      "," -> TokenType.COMMA
      "." -> TokenType.DOT
      "?." -> TokenType.QDOT
      "??" -> TokenType.COALESCE
      "!!" -> TokenType.NON_NULL
      "@" -> TokenType.AT
      "=" -> TokenType.ASSIGN
      ">" -> TokenType.GT
      "<" -> TokenType.LT
      "!" -> TokenType.NOT
      "?" -> TokenType.QUESTION
      ":" -> TokenType.COLON
      "->" -> TokenType.ARROW
      "==" -> TokenType.EQUAL
      "!=" -> TokenType.NOT_EQUAL
      "<=" -> TokenType.LTE
      ">=" -> TokenType.GTE
      "&&" -> TokenType.AND
      "||" -> TokenType.OR
      "+" -> TokenType.PLUS
      "-" -> TokenType.MINUS
      "**" -> TokenType.POW
      "*" -> TokenType.STAR
      "/" -> TokenType.DIV
      "~/" -> TokenType.INT_DIV
      "%" -> TokenType.MOD
      "|" -> TokenType.UNION
      "|>" -> TokenType.PIPE
      "..." -> TokenType.SPREAD
      "...?" -> TokenType.QSPREAD
      // TODO: tree sitter doesn't support `_` yet
      "_" -> TokenType.UNDERSCORE
      "#\"" -> TokenType.SLQuote
      "##\"" -> TokenType.SLQuote
      "###\"" -> TokenType.SLQuote
      "####\"" -> TokenType.SLQuote
      "#####\"" -> TokenType.SLQuote
      "\"" -> TokenType.SLEndQuote
      "\"#" -> TokenType.SLEndQuote
      "\"##" -> TokenType.SLEndQuote
      "\"###" -> TokenType.SLEndQuote
      "\"####" -> TokenType.SLEndQuote
      "\"#####" -> TokenType.SLEndQuote
      "#\"\"\"" -> TokenType.MLQuote
      "##\"\"\"" -> TokenType.MLQuote
      "###\"\"\"" -> TokenType.MLQuote
      "####\"\"\"" -> TokenType.MLQuote
      "#####\"\"\"" -> TokenType.MLQuote
      "\"\"\"" -> TokenType.MLEndQuote
      "\"\"\"#" -> TokenType.MLEndQuote
      "\"\"\"##" -> TokenType.MLEndQuote
      "\"\"\"###" -> TokenType.MLEndQuote
      "\"\"\"####" -> TokenType.MLEndQuote
      "\"\"\"#####" -> TokenType.MLEndQuote
      "slStringLiteralPart" -> TokenType.SLCharacters
      "mlStringLiteralPart" -> TokenType.MLCharacters
      "identifier" -> TokenType.Identifier
      "docComment" -> TokenType.DocComment
      "escapeSequence" -> TokenType.CharacterEscape
      "\n" -> TokenType.Whitespace
      // TODO: see if we need these
      //      PklParser.NewlineSemicolon -> TokenType.NewlineSemicolon
      //      PklParser.BlockComment -> TokenType.BlockComment
      //      PklParser.LineComment -> TokenType.LineComment
      //      PklParser.ShebangComment -> TokenType.ShebangComment
      //      PklParser.SLInterpolation -> TokenType.SLInterpolation
      //      PklParser.MLInterpolation -> TokenType.MLInterpolation
      //      PklParser.MLNewline -> TokenType.MLNewline
      else -> return null
    }
  return TerminalImpl(parent.project, parent, this, tokenType)
}
