/*
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
package org.pkl.lsp.packages.dto

import java.util.regex.Pattern

/** Adapted from `org.pkl.core.Version` */
data class Version(
  val major: Int,
  var minor: Int,
  val patch: Int,
  val preRelease: String?,
  val build: String?,
) : Comparable<Version> {

  /** Compares this version to the given version according to semantic versioning rules. */
  override operator fun compareTo(other: Version): Int {
    return COMPARATOR.compare(this, other)
  }

  override fun toString(): String {
    return buildString {
      append(major)
      append(".")
      append(minor)
      append(".")
      append(patch)
      if (preRelease != null) append("-").append(preRelease)
      if (build != null) append("+").append(build)
    }
  }

  private val preReleaseIdentifiers: List<Identifier> by lazy {
    preRelease
      ?.split(".")
      ?.dropLastWhile { it.isEmpty() }
      ?.map { str ->
        if (NUMERIC_IDENTIFIER.matcher(str).matches()) Identifier(str.toLong(), null)
        else Identifier(-1, str)
      } ?: emptyList()
  }

  private class Identifier(private val numericId: Long, private val alphanumericId: String?) :
    Comparable<Identifier> {
    override operator fun compareTo(other: Identifier): Int {
      return if (alphanumericId != null)
        if (other.alphanumericId != null) alphanumericId.compareTo(other.alphanumericId) else 1
      else if (other.alphanumericId != null) -1 else numericId.compareTo(other.numericId)
    }
  }

  companion object {
    // https://semver.org/#backusnaur-form-grammar-for-valid-semver-versions
    private val VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([^+]+))?(?:\\+(.+))?")
    private val NUMERIC_IDENTIFIER = Pattern.compile("(0|[1-9]\\d*)")
    private val COMPARATOR =
      Comparator.comparingInt { obj: Version -> obj.major }
        .thenComparingInt { obj: Version -> obj.minor }
        .thenComparingInt { obj: Version -> obj.patch }
        .thenComparing { v1: Version, v2: Version ->
          if (v1.preRelease == null) return@thenComparing if (v2.preRelease == null) 0 else 1
          if (v2.preRelease == null) return@thenComparing -1
          val ids1 = v1.preReleaseIdentifiers
          val ids2 = v2.preReleaseIdentifiers
          for ((id1, id2) in ids1.zip(ids2)) {
            val result = id1.compareTo(id2)
            if (result != 0) return@thenComparing result
          }
          ids1.size.compareTo(ids2.size)
        }

    val PKL_VERSION_0_26: Version = Version(0, 26, 0, null, null)
    val PKL_VERSION_0_27: Version = Version(0, 27, 0, null, null)

    /**
     * Parses the given string as a semantic version number.
     *
     * Throws [IllegalArgumentException] if the given string could not be parsed as a semantic
     * version number or is too large to fit into a [Version].
     */
    fun parse(version: String): Version {
      val result = parseOrNull(version)
      if (result != null) return result
      require(!VERSION.matcher(version).matches()) {
        String.format("`%s` is too large to fit into a Version.", version)
      }
      throw IllegalArgumentException(
        String.format("`%s` could not be parsed as a semantic version number.", version)
      )
    }

    /**
     * Parses the given string as a semantic version number.
     *
     * Returns `null` if the given string could not be parsed as a semantic version number or is too
     * large to fit into a [Version].
     */
    fun parseOrNull(version: String): Version? {
      val matcher = VERSION.matcher(version)
      return if (!matcher.matches()) null
      else
        try {
          Version(
            matcher.group(1).toInt(),
            matcher.group(2).toInt(),
            matcher.group(3).toInt(),
            matcher.group(4),
            matcher.group(5),
          )
        } catch (e: NumberFormatException) {
          null
        }
    }
  }
}
