/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.analyzers

import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.packages.dto.Version.Companion.PKL_VERSION_0_26
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.resolvers.Resolvers
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.computeExprType
import org.pkl.lsp.type.computeThisType

class AccessExprAnalyzer(project: Project) : Analyzer(project) {
  override fun doAnalyze(node: PklNode, diagnosticsHolder: DiagnosticsHolder): Boolean {
    val base = project.pklBaseModule
    val context = node.containingFile.pklProject
    if (node !is PklExpr) return true
    node.accept(
      object : PklVisitor<Unit>() {
        override fun visitUnqualifiedAccessExpr(node: PklUnqualifiedAccessExpr) {
          // don't resolve imports because whether import resolves is a separate issue/check
          val visitor =
            ResolveVisitors.firstElementNamed(node.memberNameText, base, resolveImports = false)
          // resolving unqualified access may not require `this` type so don't compute/pass it
          // upfront
          val (target, lookupMode) =
            node.resolveAndGetLookupMode(base, null, mapOf(), visitor, context)
          when (target) {
            null -> {
              val thisType = node.computeThisType(base, mapOf(), context)
              if (
                thisType == Type.Unknown || (thisType == base.dynamicType && node.isPropertyAccess)
              ) {
                return // don't flag
              }
              diagnosticsHolder.addUnresolvedAccessDiagnostic(node, thisType, base, context)
            }
            is PklMethod -> {
              checkConstAccess(node, target, diagnosticsHolder, lookupMode)
              checkArgumentCount(node, target, base, diagnosticsHolder)
            }
            is PklProperty -> {
              checkConstAccess(node, target, diagnosticsHolder, lookupMode)
              checkRecursivePropertyReference(node, target, diagnosticsHolder)
            }
          }
        }

        override fun visitQualifiedAccessExpr(element: PklQualifiedAccessExpr) {
          val receiverType = element.receiverExpr.computeExprType(base, mapOf(), context)
          if (
            receiverType == Type.Unknown ||
              (receiverType == base.dynamicType && element.isPropertyAccess)
          ) {
            return // don't flag
          }

          val visitor = ResolveVisitors.firstElementNamed(element.memberNameText, base)
          when (val target = element.resolve(base, receiverType, mapOf(), visitor, context)) {
            null -> {
              diagnosticsHolder.addUnresolvedAccessDiagnostic(element, receiverType, base, context)
            }
            is PklMethod -> {
              checkConstQualifiedAccess(element, target, diagnosticsHolder)
              checkArgumentCount(element, target, base, diagnosticsHolder)
              when (receiverType) {
                base.listType -> {
                  when {
                    target == base.listJoinMethod ||
                      target == base.listIsDistinctByMethod ||
                      target == base.listFoldMethod ||
                      target == base.listFoldIndexedMethod -> {
                      checkIsRedundantConversion(
                        element.receiverExpr,
                        base.listingToListMethod,
                        base,
                        diagnosticsHolder,
                        context,
                      )
                    }
                  }
                }
                base.mapType -> {
                  when {
                    target == base.mapContainsKeyMethod ||
                      target == base.mapGetOrNullMethod ||
                      target == base.mapFoldMethod -> {
                      checkIsRedundantConversion(
                        element.receiverExpr,
                        base.mappingToMapMethod,
                        base,
                        diagnosticsHolder,
                        context,
                      )
                    }
                  }
                }
                else -> {}
              }
            }
            is PklProperty -> {
              checkConstQualifiedAccess(element, target, diagnosticsHolder)
              if (element.receiverExpr is PklThisExpr) {
                // because `target` is the property *definition*,
                // this check won't catch all qualified recursive references
                checkRecursivePropertyReference(element, target, diagnosticsHolder)
              }
              when (receiverType) {
                base.listType -> {
                  when {
                    target == base.listIsEmptyProperty ||
                      target == base.listLengthProperty ||
                      target == base.listIsDistinctProperty -> {
                      checkIsRedundantConversion(
                        element.receiverExpr,
                        base.listingToListMethod,
                        base,
                        diagnosticsHolder,
                        context,
                      )
                    }
                  }
                }
                base.mapType -> {
                  when {
                    target == base.mapIsEmptyProperty ||
                      target == base.mapLengthProperty ||
                      target == base.mapKeysProperty -> {
                      checkIsRedundantConversion(
                        element.receiverExpr,
                        base.mappingToMapMethod,
                        base,
                        diagnosticsHolder,
                        context,
                      )
                    }
                  }
                }
                else -> {}
              }
            }
          }
        }

        override fun visitSuperAccessExpr(node: PklSuperAccessExpr) {
          val thisType = node.computeThisType(base, mapOf(), context)
          if (thisType == Type.Unknown || (thisType == base.dynamicType && node.isPropertyAccess))
            return // don't flag

          val visitor = ResolveVisitors.firstElementNamed(node.memberNameText, base)
          val target = node.resolve(base, thisType, mapOf(), visitor, context)
          if (target == null)
            diagnosticsHolder.addUnresolvedAccessDiagnostic(node, thisType, base, context)
          when (target) {
            is PklProperty -> checkConstAccess(node, target, diagnosticsHolder, null)
            is PklMethod -> checkConstAccess(node, target, diagnosticsHolder, null)
          }
        }

        override fun visitModuleExpr(o: PklModuleExpr) {
          checkConstAccess(o, diagnosticsHolder)
        }

        override fun visitThisExpr(o: PklThisExpr) {
          checkConstAccess(o, diagnosticsHolder)
        }
      }
    )
    return true
  }

  private fun DiagnosticsHolder.addUnresolvedAccessDiagnostic(
    expr: PklAccessExpr,
    receiverType: Type,
    base: PklBaseModule,
    context: PklProject?,
  ) {
    val message = ErrorMessages.create("unresolvedReference", expr.memberNameText)
    addDiagnostic(expr, message, span = expr.identifier!!.span) {
      severity =
        if (receiverType.isUnresolvedMemberFatal(base, context)) DiagnosticSeverity.Error
        else DiagnosticSeverity.Warning
      problemGroup = PklProblemGroups.unresolvedElement
    }
  }

  private fun PklNode.getConstScope(): Pair<Boolean, Boolean> {
    @Suppress("MoveVariableDeclarationIntoWhen")
    val parent = parentOfTypes(PklClassProperty::class, PklMethod::class, PklObjectBody::class)
    return when (parent) {
      is PklModifierListOwner -> parent.isConst to false
      is PklObjectBody -> {
        val isConst = parent.isConstScope()
        isConst to isConst
      }
      else -> false to false
    }
  }

  private fun checkConstAccess(
    node: IdentifierOwner,
    target: PklModifierListOwner,
    holder: DiagnosticsHolder,
    lookupMode: Resolvers.LookupMode?,
  ) {
    val (isConst, isInConstScope) = node.getConstScope()
    val targetObjectParent = target.parentOfType<PklObjectBody>()
    // if the target resides in a lexical scope within a const property, it is always allowed.
    if (targetObjectParent?.isConstScope() == true || target.isConst) return
    val isCustomThisScope =
      node
        .parentOfTypes(
          PklConstrainedType::class,
          PklMemberPredicate::class,
          /* stop class */ PklClassProperty::class,
        )
        .let { it != null && it !is PklClassProperty }
    // lookups on `this` is always allowed in custom this scopes.
    if (isCustomThisScope && lookupMode == Resolvers.LookupMode.IMPLICIT_THIS) return
    // if the lookup is in a const scope, `super` and `this` lookups are always allowed
    if (isInConstScope) {
      if (lookupMode == Resolvers.LookupMode.IMPLICIT_THIS || node.parent is PklSuperAccessExpr)
        return
      val receiverExpr = (node.parent as? PklQualifiedAccessExpr)?.receiverExpr
      if (receiverExpr is PklThisExpr) return
    }

    // scenario 1: this is a reference from a const property, and can only reference other const
    // properties
    if (isConst) {
      val name = node.identifier!!.text
      val action = if (target is PklProperty) "reference property" else "call method"
      val message = ErrorMessages.create("cannotAccessFromConst", action, name)
      holder.addError(node, message, span = node.identifier!!.span)
      return
    }

    // scenario 2: methods/properties on a module that are referenced from inside a class,
    // annotation, or typealias
    // need to be const
    val staticParent =
      node.parentOfTypes(PklClass::class, PklAnnotation::class, PklTypeAlias::class)
    if (staticParent != null) {
      if (
        target.parent is PklModule &&
          !target.isConst &&
          target.containingFile == node.containingFile
      ) {
        val action = if (target is PklProperty) "reference property" else "call method"
        val name = if (target is PklProperty) target.name else (target as PklMethod).name
        if (staticParent is PklTypeAlias) {
          // const requirement on typealiases added in Pkl 0.26. If the declared Pkl version is
          // 0.25.x, display a warning rather than an error.
          node.enclosingModule?.effectivePklVersion?.let { effectivePklVersion ->
            if (effectivePklVersion < PKL_VERSION_0_26) {
              val message = ErrorMessages.create("shouldNotAccessConstFromTypeAlias", action, name)
              holder.addWarning(node, message, span = node.identifier!!.span)
              return
            }
          }
        }
        val message =
          ErrorMessages.create("cannotAccessConstFromStaticBody", action, name.toString())
        holder.addError(node, message, span = node.identifier!!.span)
      }
    }
  }

  // TODO: provide checks for `outer`. Right now we don't have any editor support at all for `outer`
  // outside of syntax highlighting.
  private fun checkConstAccess(element: PklModuleExpr, holder: DiagnosticsHolder) {
    // `module.<prop>` is allowed even if inside a const property.
    // Instead, `<prop>` is the one that gets checked.
    if (element.parent is PklQualifiedAccessExpr) return
    val constPropertyOrMethod = element.parentOfTypes(PklClassProperty::class, PklMethod::class)
    val needsConst =
      constPropertyOrMethod?.isConst == true ||
        element.parentOfTypes(PklClass::class, PklAnnotation::class, PklTypeAlias::class) != null
    if (needsConst) {
      holder.addError(element, ErrorMessages.create("cannotReferenceModuleFromConst"))
    }
  }

  /**
   * Checks for `this` usage in the plain.
   *
   * ```
   * // not allowed (reference outside of const scope)
   * const foo = this
   *
   * // allowed (reference stays within const scope)
   * const foo = new {
   *   ["bar"] = this
   * }
   *
   * // allowed (custom this scope)
   * const foo = String(this == "bar")
   * ```
   *
   * Does not check qualified access (e.g. `const foo = this.bar`); this is handled by
   * [checkConstQualifiedAccess].
   */
  private fun checkConstAccess(element: PklThisExpr, holder: DiagnosticsHolder) {
    if (element.parent is PklQualifiedAccessExpr || element.isCustomThis()) return
    val (isConst, isInConstScope) = element.getConstScope()
    val needsConst = isConst && !isInConstScope
    if (needsConst) {
      holder.addError(element, ErrorMessages.create("cannotReferenceThisFromConst"))
    }
  }

  private fun checkConstQualifiedAccess(
    element: PklQualifiedAccessExpr,
    target: PklModifierListOwner,
    holder: DiagnosticsHolder,
  ) {
    // only need to check for const-ness if the receiver is `module` or `this`.
    if (element.receiverExpr !is PklModuleExpr && element.receiverExpr !is PklThisExpr) return
    checkConstAccess(
      element,
      target,
      holder,
      // pretend this is an implicit this lookup so that [checkConstAccess] can vet whether this
      // lookup is allowed.
      if (element.receiverExpr is PklThisExpr) Resolvers.LookupMode.IMPLICIT_THIS else null,
    )
  }

  private fun checkArgumentCount(
    expr: PklAccessExpr,
    method: PklMethod,
    base: PklBaseModule,
    holder: DiagnosticsHolder,
  ) {
    val paramList = method.methodHeader.parameterList
    val params = paramList?.elements ?: return
    val argList = expr.argumentList ?: return
    val args = argList.elements
    val paramCount = params.size
    val argCount = args.size
    when {
      argCount < paramCount -> {
        if (argCount == paramCount - 1 && method.isVarArgs(base)) return
        for (idx in argCount until paramCount) {
          val arg = buildString { renderTypedIdentifier(params[idx], mapOf()) }
          val message = ErrorMessages.create("missingArgument", arg)
          val span = args.lastOrNull()?.span?.endAt(argList.span) ?: argList.span
          holder.addError(argList, message, span = span)
        }
      }
      argCount > paramCount -> {
        if (method.isVarArgs(base)) return

        val arg = buildString {
          append(method.name)
          renderParameterList(paramList, mapOf())
        }
        val message = ErrorMessages.create("tooManyArguments", arg)
        holder.addError(args.last(), message)
      }
    }
  }

  private fun checkIsRedundantConversion(
    expr: PklExpr,
    conversionMethod: PklClassMethod,
    base: PklBaseModule,
    holder: DiagnosticsHolder,
    context: PklProject?,
  ) {
    if (expr !is PklQualifiedAccessExpr) return

    val methodName = expr.memberNameText
    val visitor = ResolveVisitors.firstElementNamed(methodName, base)
    val target = expr.resolve(base, null, mapOf(), visitor, context)
    if (target == conversionMethod) {
      val message = ErrorMessages.create("redundantConversion", methodName)
      holder.addDiagnostic(expr, message, span = expr.identifier!!.span.endAt(expr.span)) {
        severity = DiagnosticSeverity.Hint
        tags = listOf(DiagnosticTag.Unnecessary)
      }
    }
  }

  private fun checkRecursivePropertyReference(
    expr: PklAccessExpr,
    property: PklProperty,
    holder: DiagnosticsHolder,
  ) {
    val parent =
      expr.parentOfTypes(
        PklObjectMember::class,
        PklModuleMember::class, // includes `PklClassMember`
        PklFunctionLiteralExpr::class,
      )

    if (parent == property) {
      val message = ErrorMessages.create("recursivePropertyReference")
      holder.addError(expr.identifier!!, message)
    }
  }

  private fun PklThisExpr.isCustomThis(): Boolean {
    return when (
      parentOfTypes(
        PklConstrainedType::class,
        PklMemberPredicate::class,
        // stop class
        PklObjectBody::class,
      )
    ) {
      is PklConstrainedType,
      is PklMemberPredicate -> true
      else -> false
    }
  }
}
