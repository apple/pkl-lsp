package org.pkl.lsp.analyzers

import org.eclipse.lsp4j.DiagnosticSeverity
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.PklVisitor
import org.pkl.lsp.Project
import org.pkl.lsp.ast.PklAccessExpr
import org.pkl.lsp.ast.PklExpr
import org.pkl.lsp.ast.PklMethod
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.PklProperty
import org.pkl.lsp.ast.PklUnqualifiedAccessExpr
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitors
import org.pkl.lsp.type.Type
import org.pkl.lsp.type.computeThisType

class AccessExprAnalyzer(project: Project): Analyzer(project) {
  override fun doAnalyze(
    node: PklNode,
    diagnosticsHolder: MutableList<PklDiagnostic>
  ): Boolean {
    val base = project.pklBaseModule
    val context = node.containingFile.pklProject

    if (node !is PklExpr) return true
    node.accept(object : PklVisitor<Unit>() {
      override fun visitUnqualifiedAccessExpr(node: PklUnqualifiedAccessExpr) {
        // don't resolve imports because whether import resolves is a separate issue/check
        val visitor =
          ResolveVisitors.firstElementNamed(
            node.memberNameText,
            base,
            resolveImports = false,
          )
        // resolving unqualified access may not require `this` type so don't compute/pass it
        // upfront
        val (target, lookupMode) =
        node.resolveAndGetLookupMode(
            base,
            null,
            mapOf(),
            visitor,
            context
          )
        when (target) {
          null -> {
            val thisType = node.computeThisType(base, mapOf(), context)
            if (
              thisType == Type.Unknown ||
              (thisType == base.dynamicType && node.isPropertyAccess)
            ) {
              return // don't flag
            }
            diagnosticsHolder += unresolvedAccessDiagnostic(node, thisType, base, context)
          }
//          is PklMethod -> {
//            checkConstAccess(node, target, holder, lookupMode)
//            checkArgumentCount(node, target, base, holder)
//          }
//          is PklProperty -> {
//            checkConstAccess(node, target, holder, lookupMode)
//            checkRecursivePropertyReference(node, target, holder)
//          }
        }
      }
    })
    return false
  }

  private fun unresolvedAccessDiagnostic(
    expr: PklAccessExpr,
    receiverType: Type,
    base: PklBaseModule,
    context: PklProject?
  ): PklDiagnostic {
    return PklDiagnostic(
      expr,
      "Unresolved reference: ${expr.memberNameText}",
      if (receiverType.isUnresolvedMemberFatal(base, context)) DiagnosticSeverity.Error else DiagnosticSeverity.Warning,
      null
    )
  }
}