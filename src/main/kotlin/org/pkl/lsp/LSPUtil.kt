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

import com.google.gson.Gson
import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.pkl.core.util.IoUtils.encodePath
import org.pkl.lsp.ast.Span

private const val SIGNIFICAND_MASK = 0x000fffffffffffffL

private const val SIGNIFICAND_BITS = 52

private const val IMPLICIT_BIT: Long = SIGNIFICAND_MASK + 1

object LSPUtil {
  fun Span.toRange(): Range {
    val start = Position(beginLine - 1, beginCol - 1)
    val end = Position(endLine - 1, endCol - 1)
    return Range(start, end)
  }

  inline fun <reified T> List<*>.firstInstanceOf(): T? {
    return firstOrNull { it is T } as T?
  }
}

class UnexpectedTypeError(message: String) : AssertionError(message)

fun unexpectedType(obj: Any?): Nothing {
  throw UnexpectedTypeError(obj?.javaClass?.typeName ?: "null")
}

private fun takeLastSegment(name: String, separator: Char): String {
  val lastSep = name.lastIndexOf(separator)
  return name.substring(lastSep + 1)
}

private fun dropLastSegment(name: String, separator: Char): String {
  val lastSep = name.lastIndexOf(separator)
  return if (lastSep == -1) name else name.substring(0, lastSep)
}

private fun getNameWithoutExtension(path: String): String {
  val lastSep = max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  val lastDot = path.lastIndexOf('.')
  return if (lastDot == -1 || lastDot < lastSep) path.substring(lastSep + 1)
  else path.substring(lastSep + 1, lastDot)
}

fun inferImportPropertyName(moduleUriStr: String): String? {
  val moduleUri =
    try {
      URI(moduleUriStr)
    } catch (e: URISyntaxException) {
      return null
    }

  if (moduleUri.isOpaque) {
    // convention: take last segment of dot-separated name after stripping any colon-separated
    // version number
    return takeLastSegment(dropLastSegment(moduleUri.schemeSpecificPart, ':'), '.')
  }
  if (moduleUri.scheme == "package") {
    return moduleUri.fragment?.let(::getNameWithoutExtension)
  }
  if (moduleUri.isAbsolute) {
    return getNameWithoutExtension(moduleUri.path)
  }
  return getNameWithoutExtension(moduleUri.schemeSpecificPart)
}

fun isMathematicalInteger(x: Double): Boolean {
  val exponent = StrictMath.getExponent(x)
  return (exponent <= java.lang.Double.MAX_EXPONENT &&
    (x == 0.0 ||
      SIGNIFICAND_BITS - java.lang.Long.numberOfTrailingZeros(getSignificand(x)) <= exponent))
}

private fun getSignificand(d: Double): Long {
  val exponent = StrictMath.getExponent(d)
  assert(exponent <= java.lang.Double.MAX_EXPONENT)
  var bits = java.lang.Double.doubleToRawLongBits(d)
  bits = bits and SIGNIFICAND_MASK
  return if (exponent == java.lang.Double.MIN_EXPONENT - 1) bits shl 1 else bits or IMPLICIT_BIT
}

private val absoluteUriLike = Pattern.compile("\\w+:.*")

fun isAbsoluteUriLike(uriStr: String): Boolean = absoluteUriLike.matcher(uriStr).matches()

fun parseUriOrNull(uriStr: String): URI? =
  try {
    if (isAbsoluteUriLike(uriStr)) URI(uriStr) else URI(null, null, uriStr, null)
  } catch (_: URISyntaxException) {
    null
  }

val osName: String by lazy { System.getProperty("os.name") }

val isWindows: Boolean by lazy { osName.contains("Windows") }

fun Path.toUnixPathString() = if (isWindows) toString().replace("\\", "/") else toString()

val URI.effectiveUri: URI?
  get() {
    if (scheme != "pkl-lsp") return this
    val origin = Origin.fromString(authority.uppercase()) ?: return null
    val path = this.path.drop(1)
    return when (origin) {
      Origin.STDLIB -> URI("pkl:${path.replace(".pkl", "")}")
      Origin.HTTPS,
      Origin.JAR -> URI.create(path)
      else -> null
    }
  }

fun String.getIndex(position: Position): Int {
  var currentIndex = 0
  for ((column, line) in lines().withIndex()) {
    if (column == position.line) {
      return currentIndex + position.character
    }
    currentIndex += line.length + 1 // + 1 because newline is also a character
  }
  throw IllegalArgumentException("Invalid position for contents")
}

/**
 * Waits for each future to resolve, and resolves to a list of the resolved values of each future.
 */
fun <T> List<CompletableFuture<T>>.sequence(): CompletableFuture<List<T>> =
  CompletableFuture.allOf(*toTypedArray()).thenApply { map(CompletableFuture<T>::get) }

/**
 * Run [f] at most every [interval]. Every new call will reset the timer.
 *
 * If the duration between now and the last call is less than [interval], returns the previous
 * result.
 */
fun <T> debounce(interval: Duration = 5.seconds, f: () -> T): () -> T {
  var lastRun: Long? = null
  var lastResult: T? = null
  return {
    val now = System.currentTimeMillis()
    val lastRunTime = lastRun
    lastRun = now
    if (lastRunTime == null || now - lastRunTime > interval.inWholeMilliseconds) {
      f().also { result -> lastResult = result }
    } else {
      lastResult!!
    }
  }
}

interface CacheDir {
  val file: Path

  fun resolve(path: String): Path?
}

data class Package2CacheDir(override val file: Path) : CacheDir {
  override fun resolve(path: String): Path? {
    return file.resolve(encodePath(path)).let { resolved ->
      if (Files.exists(resolved)) resolved else null
    }
  }
}

data class Package1CacheDir(override val file: Path) : CacheDir {
  override fun resolve(path: String): Path? {
    return file.resolve(path).let { resolved -> if (Files.exists(resolved)) resolved else null }
  }
}

val pklCacheDir: Path = Path.of(System.getProperty("user.home")).resolve(".pkl/cache")

val packages2CacheDir: CacheDir
  get() = Package2CacheDir(pklCacheDir.resolve("package-2"))

val packages1CacheDir: CacheDir
  get() = Package1CacheDir(pklCacheDir.resolve("package-1"))

/**
 * Windows reserves characters `<>:"\|?*` in filenames.
 *
 * For any such characters, enclose their decimal character code with parentheses. Verbatim `(` is
 * encoded as `((`.
 *
 * This code is copied from `org.pkl.core.util.IoUtils.encodePath()`.
 */
fun encodePath(path: String): String {
  if (path.isEmpty()) return path
  return buildString {
    for (i in path.indices) {
      when (val character = path[i]) {
        '<',
        '>',
        ':',
        '"',
        '\\',
        '|',
        '?',
        '*' -> {
          append('(')
          append(BigInteger(byteArrayOf(character.code.toByte())).toString(16))
          append(")")
        }
        '(' -> append("((")
        else -> append(path[i])
      }
    }
  }
}

/** Decodes a path encoded with [encodePath]. */
fun decodePath(path: String): String {
  if (path.isEmpty()) return path
  return buildString {
    var i = 0
    while (i < path.length) {
      val character = path[i]
      if (character == '(') {
        require(i != path.length - 1) { "Invalid path encoding: $path" }
        i++
        var nextCharacter = path[i]
        if (nextCharacter == '(') {
          append('(')
          i++
          continue
        }
        require(nextCharacter != ')') { "Invalid path encoding: $path" }
        val codePointBuilder = StringBuilder()
        while (nextCharacter != ')') {
          when (nextCharacter) {
            '0',
            '1',
            '2',
            '3',
            '4',
            '5',
            '6',
            '7',
            '8',
            '9',
            'a',
            'b',
            'c',
            'd',
            'e',
            'f' -> codePointBuilder.append(nextCharacter)
            else -> throw IllegalArgumentException("Invalid path encoding: $path")
          }
          i++
          require(i != path.length) { "Invalid path encoding: $path" }
          nextCharacter = path[i]
        }
        append(codePointBuilder.toString().toInt(16).toChar())
      } else {
        append(character)
      }
      i++
    }
  }
}

val gson: Gson = Gson()
