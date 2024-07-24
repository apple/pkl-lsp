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

enum class TokenType {
  ABSTRACT,
  AMENDS,
  AS,
  CLASS,
  CONST,
  ELSE,
  EXTENDS,
  EXTERNAL,
  FALSE,
  FIXED,
  FOR,
  FUNCTION,
  HIDDEN,
  IF,
  IMPORT,
  IMPORT_GLOB,
  IN,
  IS,
  LET,
  LOCAL,
  MODULE,
  NEW,
  NOTHING,
  NULL,
  OPEN,
  OUT,
  OUTER,
  READ,
  READ_GLOB,
  READ_OR_NULL,
  SUPER,
  THIS,
  THROW,
  TRACE,
  TRUE,
  TYPE_ALIAS,
  UNKNOWN,
  WHEN,
  PROTECTED,
  OVERRIDE,
  RECORD,
  DELETE,
  CASE,
  SWITCH,
  VARARG,
  LPAREN,
  RPAREN,
  LBRACE,
  RBRACE,
  LBRACK,
  RBRACK,
  LPRED,
  COMMA,
  DOT,
  QDOT,
  COALESCE,
  NON_NULL,
  AT,
  ASSIGN,
  GT,
  LT,
  NOT,
  QUESTION,
  COLON,
  ARROW,
  EQUAL,
  NOT_EQUAL,
  LTE,
  GTE,
  AND,
  OR,
  PLUS,
  MINUS,
  POW,
  STAR,
  DIV,
  INT_DIV,
  MOD,
  UNION,
  PIPE,
  SPREAD,
  QSPREAD,
  UNDERSCORE,
  SLQuote,
  MLQuote,
  IntLiteral,
  FloatLiteral,
  Identifier,
  NewlineSemicolon,
  Whitespace,
  DocComment,
  BlockComment,
  LineComment,
  ShebangComment,
  SLEndQuote,
  SLInterpolation,
  SLUnicodeEscape,
  SLCharacterEscape,
  SLCharacters,
  MLEndQuote,
  MLInterpolation,
  MLUnicodeEscape,
  MLCharacterEscape,
  MLNewline,
  MLCharacters,
}

val modifierTypes =
  setOf(
    TokenType.EXTERNAL,
    TokenType.ABSTRACT,
    TokenType.OPEN,
    TokenType.LOCAL,
    TokenType.HIDDEN,
    TokenType.FIXED,
    TokenType.CONST,
  )
