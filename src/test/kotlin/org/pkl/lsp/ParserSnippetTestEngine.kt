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

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.*
import kotlin.reflect.KClass
import org.assertj.core.api.Assertions.fail
import org.assertj.core.util.diff.DiffUtils
import org.junit.platform.engine.*
import org.junit.platform.engine.TestDescriptor.Type
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.MethodSelector
import org.junit.platform.engine.discovery.PackageSelector
import org.junit.platform.engine.discovery.UniqueIdSelector
import org.junit.platform.engine.support.descriptor.*
import org.junit.platform.engine.support.hierarchical.EngineExecutionContext
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine
import org.junit.platform.engine.support.hierarchical.Node
import org.junit.platform.engine.support.hierarchical.Node.DynamicTestExecutor
import org.opentest4j.AssertionFailedError
import org.pkl.lsp.ast.PklModuleImpl
import org.pkl.lsp.ast.PklNode
import org.pkl.lsp.ast.Terminal

class ParserSnippetTestEngine : HierarchicalTestEngine<ParserSnippetTestEngine.ExecutionContext>() {

  private val currentWorkingDir: Path
    get() = Path.of(System.getProperty("user.dir"))

  private val rootProjectDir: Path by lazy {
    val workingDir = currentWorkingDir
    workingDir.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: workingDir.parent.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: workingDir.parent.parent.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: throw AssertionError("Failed to locate root project directory.")
  }

  private val testClass: KClass<*> = ParserSnippetTests::class

  private val includedTests: List<Regex> = listOf(Regex(".*"))

  @Suppress("RegExpUnexpectedAnchor") private val excludedTests: List<Regex> = listOf(Regex("$^"))

  private val inputDir: Path = rootProjectDir.resolve("src/test/files/ParserSnippetTests/inputs")

  private val outputDir: Path = inputDir.resolve("../outputs")

  private val isInputFile: (Path) -> Boolean = { it.isRegularFile() && it.extension == "pkl" }

  private fun expectedOutputFileFor(inputFile: Path): Path {
    val relativePath = inputDir.relativize(inputFile)
    return outputDir.resolve(relativePath.toString().dropLast(4) + ".txt")
  }

  private val project = Project(PklLSPServer(true))

  private fun PklNode.render(): String {
    return buildString {
      doRender(this)
      appendLine()
    }
  }

  private fun PklNode.doRender(sb: StringBuilder, indent: String = "", isFirst: Boolean = true) {
    if (this is Terminal) return
    if (!isFirst) {
      sb.append("\n")
    }
    sb.append(indent)
    sb.append(this::class.simpleName)
    for (child in this.children) {
      child.doRender(sb, "$indent  ", false)
    }
  }

  private fun generateOutputFor(inputFile: Path): Pair<Boolean, String> {
    return try {
      val text = inputFile.readText()
      val tree = project.pklParser.parse(text)
      val mod = PklModuleImpl(tree.rootNode, project.virtualFileManager.getEphemeral(text))
      true to mod.render()
    } catch (err: Throwable) {
      false to err.stackTraceToString()
    }
  }

  class ExecutionContext : EngineExecutionContext

  override fun getId(): String = this::class.java.simpleName

  init {
    // Enforce consistent locale for tests to avoid inconsistent formatting.
    Locale.setDefault(Locale.ROOT)
  }

  override fun discover(
    discoveryRequest: EngineDiscoveryRequest,
    uniqueId: UniqueId,
  ): TestDescriptor {
    val packageSelectors = discoveryRequest.getSelectorsByType(PackageSelector::class.java)
    val classSelectors = discoveryRequest.getSelectorsByType(ClassSelector::class.java)
    val methodSelectors = discoveryRequest.getSelectorsByType(MethodSelector::class.java)
    val uniqueIdSelectors = discoveryRequest.getSelectorsByType(UniqueIdSelector::class.java)

    val packageName = testClass.java.`package`.name
    val className = testClass.java.name

    if (
      methodSelectors.isEmpty() &&
        (packageSelectors.isEmpty() || packageSelectors.any { it.packageName == packageName }) &&
        (classSelectors.isEmpty() || classSelectors.any { it.className == className })
    ) {

      val rootNode = object : InputDirNode(uniqueId, inputDir, ClassSource.from(testClass.java)) {}
      return doDiscover(rootNode, uniqueIdSelectors)
    }

    // return empty descriptor w/o children
    return EngineDescriptor(uniqueId, javaClass.simpleName)
  }

  private fun doDiscover(
    dirNode: InputDirNode,
    uniqueIdSelectors: List<UniqueIdSelector>,
  ): TestDescriptor {
    dirNode.inputDir.useDirectoryEntries { children ->
      for (child in children) {
        val testPath = child.toNormalizedPathString()
        val testName = child.fileName.toString()
        if (child.isRegularFile()) {
          if (
            isInputFile(child) &&
              includedTests.any { it.matches(testPath) } &&
              !excludedTests.any { it.matches(testPath) }
          ) {
            val childId = dirNode.uniqueId.append("inputFileNode", testName)
            if (
              uniqueIdSelectors.isEmpty() ||
                uniqueIdSelectors.any { childId.hasPrefix(it.uniqueId) }
            ) {
              dirNode.addChild(InputFileNode(childId, child))
            } // else skip
          }
        } else {
          val childId = dirNode.uniqueId.append("inputDirNode", testName)
          dirNode.addChild(
            doDiscover(
              InputDirNode(childId, child, DirectorySource.from(child.toFile())),
              uniqueIdSelectors,
            )
          )
        }
      }
    }
    return dirNode
  }

  override fun createExecutionContext(request: ExecutionRequest) = ExecutionContext()

  private open inner class InputDirNode(
    uniqueId: UniqueId,
    val inputDir: Path,
    source: TestSource,
  ) :
    AbstractTestDescriptor(uniqueId, inputDir.fileName.toString(), source), Node<ExecutionContext> {
    override fun getType() = Type.CONTAINER
  }

  private inner class InputFileNode(uniqueId: UniqueId, private val inputFile: Path) :
    AbstractTestDescriptor(
      uniqueId,
      inputFile.fileName.toString(),
      FileSource.from(inputFile.toFile()),
    ),
    Node<ExecutionContext> {

    override fun getType() = Type.TEST

    override fun execute(
      context: ExecutionContext,
      dynamicTestExecutor: DynamicTestExecutor,
    ): ExecutionContext {

      val (success, actualOutput) = generateOutputFor(inputFile)
      val expectedOutputFile = expectedOutputFileFor(inputFile)

      SnippetOutcome(expectedOutputFile, actualOutput, success).check()

      return context
    }
  }

  data class SnippetOutcome(val expectedOutFile: Path, val actual: String, val success: Boolean) {
    private val expectedErrFile =
      expectedOutFile.resolveSibling(expectedOutFile.toString().replaceAfterLast('.', "err"))

    private val expectedOutExists = expectedOutFile.exists()
    private val expectedErrExists = expectedErrFile.exists()
    private val overwrite
      get() = System.getenv().containsKey("OVERWRITE_SNIPPETS")

    private val expected by lazy {
      when {
        expectedOutExists && expectedErrExists ->
          fail("Test has both expected out and .err files: $displayName")
        expectedOutExists -> expectedOutFile.readText()
        expectedErrExists -> expectedErrFile.readText()
        else -> ""
      }
    }

    private val displayName by lazy {
      val path = expectedOutFile.toString()
      val baseDir = "src/test/files"
      val index = path.indexOf(baseDir)
      val endIndex = path.lastIndexOf('.')
      if (index == -1 || endIndex == -1) path else path.substring(index + baseDir.length, endIndex)
    }

    fun check() {
      when {
        success && !expectedOutExists && !expectedErrExists && actual.isBlank() -> return
        !success && expectedOutExists && !overwrite ->
          failWithDiff("Test was expected to succeed, but failed: $displayName")
        !success && expectedOutExists -> {
          expectedOutFile.deleteExisting()
          expectedErrFile.writeText(actual)
          fail("Wrote file $expectedErrFile for $displayName and deleted $expectedOutFile")
        }
        success && expectedErrExists && !overwrite ->
          failWithDiff("Test was expected to fail, but succeeded: $displayName")
        success && expectedErrExists -> {
          expectedErrFile.deleteExisting()
          expectedOutFile.writeText(actual)
          fail("Wrote file $expectedOutFile for $displayName and deleted $expectedErrFile")
        }
        !expectedOutExists && !expectedErrExists && actual.isNotBlank() -> {
          val file = if (success) expectedOutFile else expectedErrFile
          file.createParentDirectories().writeText(actual)
          failWithDiff("Created missing file $file for $displayName")
        }
        else -> {
          assert(success && expectedOutExists || !success && expectedErrExists)
          if (actual != expected) {
            if (overwrite) {
              val file = if (success) expectedOutFile else expectedErrFile
              file.writeText(actual)
              fail("Overwrote file $file for $displayName")
            } else {
              failWithDiff("Output was different from expected: $displayName")
            }
          }
        }
      }
    }

    private fun failWithDiff(message: String): Nothing =
      throw PklAssertionFailedError(message, expected, actual)
  }

  /**
   * Makes up for the fact that [AssertionFailedError] doesn't print a diff, resulting in
   * unintelligible errors outside IDEs (which show a diff dialog).
   * https://github.com/ota4j-team/opentest4j/issues/59
   */
  class PklAssertionFailedError(message: String, expected: Any?, actual: Any?) :
    AssertionFailedError(message, expected, actual) {
    override fun toString(): String {
      val patch =
        DiffUtils.diff(expected.stringRepresentation.lines(), actual.stringRepresentation.lines())
      return patch.deltas.joinToString("\n\n")
    }
  }

  private fun Path.toNormalizedPathString(): String {
    if (isWindows) {
      return toString().replace("\\", "/")
    }
    return toString()
  }
}
