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
package org.pkl.lsp

import org.pkl.lsp.ast.*

open class PklVisitor<R> {

  open fun visitAccessExpr(node: PklAccessExpr): R? {
    return visitExpr(node)
  }

  open fun visitAdditiveExpr(node: PklAdditiveExpr): R? {
    return visitExpr(node)
  }

  open fun visitAmendExpr(node: PklAmendExpr): R? {
    return visitExpr(node)
  }

  open fun visitAnnotation(node: PklAnnotation): R? {
    return visitObjectBodyOwner(node)
  }

  open fun visitArgumentList(node: PklArgumentList): R? {
    return visitElement(node)
  }

  open fun visitClass(node: PklClass): R? {
    return visitModuleMember(node)
  }

  open fun visitClassBody(node: PklClassBody): R? {
    return visitElement(node)
  }

  open fun visitClassMember(node: PklClassMember): R? {
    return visitModuleMember(node)
  }

  open fun visitClassMethod(node: PklClassMethod): R? {
    return visitClassMember(node)
  }

  open fun visitClassProperty(node: PklClassProperty): R? {
    return visitClassMember(node)
  }

  open fun visitComparisonExpr(node: PklComparisonExpr): R? {
    return visitExpr(node)
  }

  open fun visitConstrainedType(node: PklConstrainedType): R? {
    return visitType(node)
  }

  open fun visitDeclaredType(node: PklDeclaredType): R? {
    return visitType(node)
  }

  open fun visitDefaultUnionType(node: PklDefaultUnionType): R? {
    return visitType(node)
  }

  open fun visitElement(node: PklNode): R? {
    return null
  }

  open fun visitEqualityExpr(node: PklEqualityExpr): R? {
    return visitExpr(node)
  }

  open fun visitError(node: PklError): R? {
    return visitElement(node)
  }

  open fun visitExponentiationExpr(node: PklExponentiationExpr): R? {
    return visitExpr(node)
  }

  open fun visitExpr(node: PklExpr): R? {
    return visitElement(node)
  }

  open fun visitFalseLiteralExpr(node: PklFalseLiteralExpr): R? {
    return visitExpr(node)
  }

  open fun visitFloatLiteralExpr(node: PklFloatLiteralExpr): R? {
    return visitExpr(node)
  }

  open fun visitForGenerator(node: PklForGenerator): R? {
    return visitObjectMember(node)
  }

  open fun visitFunctionLiteral(node: PklFunctionLiteralExpr): R? {
    return visitExpr(node)
  }

  open fun visitFunctionType(node: PklFunctionType): R? {
    return visitType(node)
  }

  open fun visitIdentifierOwner(node: IdentifierOwner): R? {
    return visitElement(node)
  }

  open fun visitIfExpr(node: PklIfExpr): R? {
    return visitExpr(node)
  }

  open fun visitImport(node: PklImport): R? {
    return visitElement(node)
  }

  open fun visitImportExpr(node: PklImportExpr): R? {
    return visitExpr(node)
  }

  open fun visitIntLiteralExpr(node: PklIntLiteralExpr): R? {
    return visitExpr(node)
  }

  open fun visitLetExpr(node: PklLetExpr): R? {
    return visitExpr(node)
  }

  open fun visitLogicalAndExpr(node: PklLogicalAndExpr): R? {
    return visitExpr(node)
  }

  open fun visitLogicalNotExpr(node: PklLogicalNotExpr): R? {
    return visitExpr(node)
  }

  open fun visitLogicalOrExpr(node: PklLogicalOrExpr): R? {
    return visitExpr(node)
  }

  open fun visitMemberPredicate(node: PklMemberPredicate): R? {
    return visitObjectMember(node)
  }

  open fun visitMethodHeader(node: PklMethodHeader): R? {
    return visitModifierListOwner(node)
  }

  open fun visitMlStringLiteral(node: PklMultiLineStringLiteral): R? {
    return visitExpr(node)
  }

  open fun visitModifierListOwner(node: PklModifierListOwner): R? {
    return visitElement(node)
  }

  open fun visitModule(node: PklModule): R? {
    return visitElement(node)
  }

  open fun visitModuleHeader(node: PklModuleHeader): R? {
    return visitDocCommentOwner(node)
  }

  open fun visitModuleExpr(node: PklModuleExpr): R? {
    return visitExpr(node)
  }

  open fun visitModuleExtendsAmendsClause(node: PklModuleExtendsAmendsClause): R? {
    return visitElement(node)
  }

  open fun visitModuleClause(node: PklModuleClause): R? {
    return visitElement(node)
  }

  open fun visitModuleMember(node: PklModuleMember): R? {
    return visitElement(node)
  }

  open fun visitModuleType(node: PklModuleType): R? {
    return visitType(node)
  }

  open fun visitModuleUri(node: PklModuleUri): R? {
    return visitElement(node)
  }

  open fun visitMultiplicativeExpr(node: PklMultiplicativeExpr): R? {
    return visitExpr(node)
  }

  open fun visitNewExpr(node: PklNewExpr): R? {
    return visitExpr(node)
  }

  open fun visitNonNullExpr(node: PklNonNullExpr): R? {
    return visitExpr(node)
  }

  open fun visitNothingType(node: PklNothingType): R? {
    return visitType(node)
  }

  open fun visitNullCoalesceExpr(node: PklNullCoalesceExpr): R? {
    return visitExpr(node)
  }

  open fun visitNullLiteralExpr(node: PklNullLiteralExpr): R? {
    return visitExpr(node)
  }

  open fun visitNullableType(node: PklNullableType): R? {
    return visitType(node)
  }

  open fun visitObjectBody(node: PklObjectBody): R? {
    return visitElement(node)
  }

  open fun visitObjectBodyOwner(node: PklObjectBodyOwner): R? {
    return visitElement(node)
  }

  open fun visitObjectElement(node: PklObjectElement): R? {
    return visitObjectMember(node)
  }

  open fun visitObjectEntry(node: PklObjectEntry): R? {
    return visitObjectMember(node)
  }

  open fun visitObjectMember(node: PklObjectMember): R? {
    return visitElement(node)
  }

  open fun visitObjectMethod(node: PklObjectMethod): R? {
    return visitObjectMember(node)
  }

  open fun visitObjectProperty(node: PklObjectProperty): R? {
    return visitObjectMember(node)
  }

  open fun visitObjectSpread(node: PklObjectSpread): R? {
    return visitObjectMember(node)
  }

  open fun visitOuterExpr(node: PklOuterExpr): R? {
    return visitExpr(node)
  }

  open fun visitParenthesizedExpr(node: PklParenthesizedExpr): R? {
    return visitExpr(node)
  }

  open fun visitParenthesizedType(node: PklParenthesizedType): R? {
    return visitType(node)
  }

  open fun visitParameterList(node: PklParameterList): R? {
    return visitElement(node)
  }

  open fun visitPipeExpr(node: PklPipeExpr): R? {
    return visitExpr(node)
  }

  open fun visitQualifiedAccessExpr(node: PklQualifiedAccessExpr): R? {
    return visitAccessExpr(node)
  }

  open fun visitQualifiedIdentifier(node: PklQualifiedIdentifier): R? {
    return visitElement(node)
  }

  open fun visitReadExpr(node: PklReadExpr): R? {
    return visitExpr(node)
  }

  open fun visitSimpleTypeName(node: PklSimpleTypeName): R? {
    return visitIdentifierOwner(node)
  }

  open fun visitModuleName(node: PklModuleName): R? {
    return visitIdentifierOwner(node)
  }

  open fun visitStringConstant(node: PklStringConstant): R? {
    return visitElement(node)
  }

  open fun visitStringLiteral(node: PklSingleLineStringLiteral): R? {
    return visitExpr(node)
  }

  open fun visitStringLiteralType(node: PklStringLiteralType): R? {
    return visitType(node)
  }

  open fun visitSubscriptExpr(node: PklSubscriptExpr): R? {
    return visitExpr(node)
  }

  open fun visitSuperAccessExpr(node: PklSuperAccessExpr): R? {
    return visitAccessExpr(node)
  }

  open fun visitSuperSubscriptExpr(node: PklSuperSubscriptExpr): R? {
    return visitExpr(node)
  }

  open fun visitTerminal(node: Terminal): R? {
    return visitElement(node)
  }

  open fun visitThisExpr(node: PklThisExpr): R? {
    return visitExpr(node)
  }

  open fun visitThrowExpr(node: PklThrowExpr): R? {
    return visitExpr(node)
  }

  open fun visitTraceExpr(node: PklTraceExpr): R? {
    return visitExpr(node)
  }

  open fun visitTrueLiteralExpr(node: PklTrueLiteralExpr): R? {
    return visitExpr(node)
  }

  open fun visitType(node: PklType): R? {
    return visitElement(node)
  }

  open fun visitTypeAlias(node: PklTypeAlias): R? {
    return visitModuleMember(node)
  }

  open fun visitTypeAnnotation(node: PklTypeAnnotation): R? {
    return visitElement(node)
  }

  open fun visitTypeArgumentList(node: PklTypeArgumentList): R? {
    return visitElement(node)
  }

  open fun visitTypeName(node: PklTypeName): R? {
    return visitElement(node)
  }

  open fun visitTypeParameter(node: PklTypeParameter): R? {
    return visitElement(node)
  }

  open fun visitTypeParameterList(node: PklTypeParameterList): R? {
    return visitElement(node)
  }

  open fun visitTypeTestExpr(node: PklTypeTestExpr): R? {
    return visitExpr(node)
  }

  open fun visitTypeCastExpr(node: PklTypeCastExpr): R? {
    return visitExpr(node)
  }

  open fun visitTypedIdentifier(node: PklTypedIdentifier): R? {
    return visitIdentifierOwner(node)
  }

  open fun visitUnaryMinusExpr(node: PklUnaryMinusExpr): R? {
    return visitExpr(node)
  }

  open fun visitUnionType(node: PklUnionType): R? {
    return visitType(node)
  }

  open fun visitUnknownType(node: PklUnknownType): R? {
    return visitType(node)
  }

  open fun visitUnqualifiedAccessExpr(node: PklUnqualifiedAccessExpr): R? {
    return visitAccessExpr(node)
  }

  open fun visitWhenGenerator(node: PklWhenGenerator): R? {
    return visitObjectMember(node)
  }

  open fun visitDocCommentOwner(node: PklDocCommentOwner): R? {
    return visitElement(node)
  }

  open fun visitLineComment(node: PklLineComment): R? {
    return null
  }

  open fun visitBlockComment(node: PklBlockComment): R? {
    return null
  }

  open fun visitShebangComment(node: PklShebangComment): R? {
    return null
  }
}
