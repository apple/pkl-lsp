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
package org.pkl.lsp.util

import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import org.pkl.lsp.VirtualFile

object GlobResolver {

  private val cache = Collections.synchronizedMap(WeakHashMap<String, Pattern>())

  private const val NULL = 0.toChar()

  /**
   * The maximum number of [VirtualFile.getChildren] calls to be made when resolving a single glob
   * pattern (prevents an expensive glob from halting the CPU and memory, e.g. a long string of `*`
   * and `..`)
   */
  private const val MAX_LIST_ELEMENTS = 16384

  private fun getNextChar(pattern: String, i: Int): Char =
    if (i >= pattern.length - 1) NULL else pattern[i + 1]

  private fun consumeCharacterClass(globPattern: String, idx: Int, sb: StringBuilder): Int {
    // don't match path separators
    sb.append("[[^/]&&[")
    var i = idx
    when (getNextChar(globPattern, i)) {
      '^' -> {
        // verbatim; escape
        sb.append("\\^")
        i++
      }
      '!' -> {
        // negation
        sb.append("^")
        i++
      }
      ']' -> {
        // the first `]` in a character class is verbatim and not treated as a closing delimiter.
        sb.append(']')
        i++
      }
      NULL -> return -1
    }
    i++
    var current = globPattern[i]
    while (current != ']') {
      if (current == '[') {
        val next = getNextChar(globPattern, i)
        if (next == ':' || next == '=' || next == '.') {
          return -1
        }
      }
      when (current) {
        '/' -> {
          return -1
        }
        '\\' -> {
          sb.append("\\\\")
        }
        else -> {
          sb.append(current)
        }
      }
      i++
      if (i == globPattern.length) {
        return -1
      }
      current = globPattern[i]
    }
    sb.append("]]")
    return i
  }

  private fun toRegexPattern(globPattern: String): Pattern? {
    return cache.getOrElse(globPattern) { toRegexString(globPattern)?.let(Pattern::compile) }
  }

  /**
   * Converts a glob pattern to an equivalent regular expression pattern
   *
   * Copied from pkl/pkl.
   */
  private fun toRegexString(globPattern: String): String? {
    val sb = StringBuilder("^")
    var inGroup = false
    var i = 0
    while (i < globPattern.length) {
      when (val current = globPattern[i]) {
        '{' -> {
          if (inGroup) {
            return null
          }
          inGroup = true
          sb.append("(?:(?:")
        }
        '}' -> {
          if (inGroup) {
            inGroup = false
            sb.append("))")
          } else {
            sb.append('}')
          }
        }
        ',' -> {
          if (inGroup) {
            sb.append(")|(?:")
          } else {
            sb.append(',')
          }
        }
        '\\' -> {
          val next = getNextChar(globPattern, i)
          if (next == NULL) {
            return null
          }
          if (next != '?' && next != '*' && next != '[' && next != '{' && next != '\\') {
            return null
          }
          sb.append('\\').append(next)
          i++
        }
        '[' -> {
          i = consumeCharacterClass(globPattern, i, sb)
          if (i == -1) {
            return null
          }
        }
        '?' -> {
          val next = getNextChar(globPattern, i)
          if (next == '(') {
            return null
          }
          sb.append(".")
        }
        '*' -> {
          when (getNextChar(globPattern, i)) {
            '(' -> {
              return null
            }
            '*' -> {
              // globstar, crosses directory boundaries
              sb.append(".*")
              i++
            }
            else -> {
              // single wildcard matches everything up until the next directory character
              sb.append("[^/]*")
            }
          }
        }
        '+',
        '@' -> {
          val next = getNextChar(globPattern, i)
          if (next == '(') {
            return null
          }
          sb.append("\\+")
        }
        '!' -> {
          val next = getNextChar(globPattern, i)
          if (next == '(') {
            return null
          }
          sb.append("!")
        }
        '.',
        '(',
        '%',
        '^',
        '$',
        '|' -> {
          sb.append("\\").append(current)
        }
        else -> sb.append(current)
      }
      i++
    }
    if (inGroup) {
      return null
    }
    return sb.append("$").toString()
  }

  fun isRegularPathPart(pathPart: String): Boolean {
    for (element in pathPart) {
      when (element) {
        '[',
        '{',
        '\\',
        '*',
        '?' -> return false
      }
    }
    return true
  }

  private fun gatherAllChildren(
    virtualFile: VirtualFile,
    listChildren: (VirtualFile) -> List<VirtualFile>,
    filter: (VirtualFile) -> Boolean,
    result: MutableList<VirtualFile> = mutableListOf(),
  ): List<VirtualFile> {
    val children = listChildren(virtualFile)
    result.addAll(children.filter(filter))
    for (child in children) {
      if (child.isDirectory) {
        gatherAllChildren(child, listChildren, filter, result)
      }
    }
    return result
  }

  private fun expandGlobPart(
    baseFile: VirtualFile,
    globPart: String,
    listElementCallCount: AtomicInteger,
    listChildren: (VirtualFile) -> List<VirtualFile>,
    isPartial: Boolean,
  ): List<VirtualFile>? {
    if (listElementCallCount.getAndIncrement() > MAX_LIST_ELEMENTS) {
      return null
    }
    val pattern = toRegexPattern(globPart) ?: return null
    if (globPart.contains("**")) {
      return gatherAllChildren(
        baseFile,
        listChildren,
        { file ->
          val path = baseFile.path.relativize(file.path).toString()
          val matches = pattern.matcher(path).matches()
          if (isPartial) matches && file.isDirectory else matches
        },
      )
    }
    return baseFile.children
      ?.filter { file ->
        val matches = pattern.matcher(file.name).matches()
        if (isPartial) matches && file.isDirectory else matches
      }
      ?.toList()
  }

  /**
   * @param globPatternParts The glob pattern, split by the file separator
   * @param idx The index of the current glob pattern part being expanded
   * @param currentDir The current directory being expanded
   * @param listElementCallCount The number of times we have listed children
   * @param isPartial Whether [globPatternParts] represents the whole URI
   * @param result The return value
   */
  private fun expandGlob(
    globPatternParts: List<String>,
    idx: Int,
    currentDir: VirtualFile,
    listElementCallCount: AtomicInteger,
    isPartial: Boolean,
    listChildren: (VirtualFile) -> List<VirtualFile>,
    result: MutableList<VirtualFile>,
  ) {
    val patternPart = globPatternParts[idx]
    val isLeaf = idx == globPatternParts.size - 1
    // no expanding needed, carry on
    if (isRegularPathPart(patternPart)) {
      val child =
        when (patternPart) {
          "." -> currentDir
          ".." -> currentDir.parent()
          else -> listChildren(currentDir).find { it.name == patternPart }
        } ?: return
      if (isLeaf) {
        result.add(child)
        return
      }
      if (child.isDirectory) {
        expandGlob(
          globPatternParts,
          idx + 1,
          child,
          listElementCallCount,
          isPartial,
          listChildren,
          result,
        )
      }
    } else {
      val expandedFiles =
        expandGlobPart(currentDir, patternPart, listElementCallCount, listChildren, isPartial)
          ?: return
      for (file in expandedFiles) {
        if (isLeaf) {
          result.add(file)
        } else if (file.isDirectory) {
          expandGlob(
            globPatternParts,
            idx + 1,
            file,
            listElementCallCount,
            isPartial,
            listChildren,
            result,
          )
        }
      }
    }
  }

  fun resolveRelativeGlob(
    enclosingDirectory: VirtualFile,
    globPattern: String,
    isPartial: Boolean,
    listChildren: (VirtualFile) -> List<VirtualFile>,
  ): List<VirtualFile> {
    if (globPattern.isEmpty()) return listOf(enclosingDirectory)
    val result = ArrayList<VirtualFile>()
    val globParts = globPattern.split("/")
    if (globParts.isEmpty()) return emptyList()
    expandGlob(globParts, 0, enclosingDirectory, AtomicInteger(0), isPartial, listChildren, result)
    return result
  }

  fun resolveAbsoluteGlob(
    rootFile: VirtualFile,
    globPattern: String,
    isPartial: Boolean,
    listChildren: (VirtualFile) -> List<VirtualFile>,
  ): List<VirtualFile> {
    if (globPattern == "/") return listOf(rootFile)
    val result = ArrayList<VirtualFile>()
    val globParts = globPattern.split("/").drop(1)
    if (globParts.isEmpty()) return emptyList()
    expandGlob(globParts, 0, rootFile, AtomicInteger(0), isPartial, listChildren, result)
    return result
  }
}
