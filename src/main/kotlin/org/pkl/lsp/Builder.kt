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
package org.pkl.lsp

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.pkl.core.parser.LexParseException
import org.pkl.core.parser.Parser
import org.pkl.lsp.LSPUtil.toRange
import org.pkl.lsp.analyzers.*
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.ast.PklModuleImpl
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.Span

class Builder(private val server: PklLSPServer, project: Project) : Component(project) {
  private val runningBuild: MutableMap<String, CompletableFuture<PklModule?>> = mutableMapOf()

  private val parser = Parser()

  private val analyzers: List<Analyzer> =
    listOf(
      ModifierAnalyzer(project),
      AnnotationAnalyzer(project),
      SyntaxAnalyzer(project),
      ModuleUriAnalyzer(project),
      ModuleMemberAnalyzer(project),
    )

  fun runningBuild(uri: String): CompletableFuture<PklModule?> =
    runningBuild[uri] ?: CompletableFuture.supplyAsync(::noop)

  fun requestBuild(
    uri: URI,
    vfile: VirtualFile,
    fileContents: String? = null,
  ): CompletableFuture<PklModule?> {
    val build = CompletableFuture.supplyAsync { build(uri, vfile, fileContents) }
    runningBuild[uri.toString()] = build
    return build
  }

  fun lastSuccessfulBuild(uri: String): PklModule? = buildCache[URI.create(uri)]

  private fun build(file: URI, vfile: VirtualFile, fileContents: String?): PklModule? {
    return try {
      val contents = fileContents ?: vfile.contents()
      logger.log("building $file")
      val moduleCtx = parser.parseModule(contents)
      val module = PklModuleImpl(moduleCtx, file, vfile)
      val diagnostics = analyze(module)
      makeDiagnostics(file, diagnostics.map { it.toMessage() })
      buildCache[file] = module
      diagnosticsCache[file] = diagnostics
      return module
    } catch (e: LexParseException) {
      logger.error("Parser Error building $file: ${e.message}")
      makeParserDiagnostics(file, listOf(toParserError(e)))
      null
    } catch (e: Exception) {
      logger.error("Error building $file: ${e.message} ${e.stackTraceToString()}")
      null
    }
  }

  private fun analyze(node: PklNode): List<PklDiagnostic> {
    return buildList {
      for (analyzer in analyzers) {
        analyzer.analyze(node, this)
      }
    }
  }

  private fun makeParserDiagnostics(file: URI, errors: List<ParseError>) {
    val diags =
      errors.map { err ->
        val msg = ErrorMessages.create(err.errorType, *err.args)
        val diag = Diagnostic(err.span.toRange(), "$msg\n\n")
        diag.severity = DiagnosticSeverity.Error
        diag.source = "Pkl Language Server"
        logger.log("diagnostic: $msg at ${err.span}")
        diag
      }
    makeDiagnostics(file, diags)
  }

  private fun makeDiagnostics(file: URI, diags: List<Diagnostic>) {
    logger.log("Found ${diags.size} diagnostic errors for $file")
    val params = PublishDiagnosticsParams(file.toString(), diags)
    // Have to publish diagnostics even if there are no errors, so we clear previous problems
    server.client().publishDiagnostics(params)
  }

  companion object {
    private fun noop(): PklModule? {
      return null
    }

    fun pathToModule(path: Path, virtualFile: VirtualFile): PklModule? {
      if (!Files.exists(path) || path.isDirectory()) return null
      val change = path.readText()
      return fileToModule(change, path.normalize().toUri(), virtualFile)
    }

    fun fileToModule(contents: String, uri: URI, virtualFile: VirtualFile): PklModule? {
      val parser = Parser()
      try {
        val moduleCtx = parser.parseModule(contents)
        return PklModuleImpl(moduleCtx, uri, virtualFile).also { buildCache[uri] = it }
      } catch (_: IOException) {
        return null
      }
    }

    private fun toParserError(ex: LexParseException): ParseError {
      val span = Span(ex.line, ex.column, ex.line, ex.column + ex.length)
      return ParseError(ex.message ?: "Parser error", span)
    }

    private val buildCache: MutableMap<URI, PklModule> = ConcurrentHashMap()

    val diagnosticsCache: MutableMap<URI, List<PklDiagnostic>> = ConcurrentHashMap()

    fun findModuleInCache(uri: URI): PklModule? = buildCache[uri]
  }
}

class ParseError(val errorType: String, val span: Span, vararg val args: Any)
