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
package org.pkl.lsp.packages.dto

import java.net.URI
import java.net.URISyntaxException
import java.util.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.pkl.lsp.encodePath
import org.pkl.lsp.packages.PackageDependency

@Suppress("PROVIDED_RUNTIME_TOO_LOW")
@Serializable(with = PackageUri.Companion.Serializer::class)
data class PackageUri(
  val authority: String,
  val path: String,
  val version: Version,
  val checksums: Checksums?,
) {
  private val basePath = path.substringBeforeLast('@')

  companion object {
    object Serializer : KSerializer<PackageUri> {
      override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("PackageUri", PrimitiveKind.STRING)

      override fun deserialize(decoder: Decoder): PackageUri {
        val str = decoder.decodeString()
        return create(str) ?: throw IllegalArgumentException("Invalid package uri: $str")
      }

      override fun serialize(encoder: Encoder, value: PackageUri) {
        encoder.encodeString(value.toString())
      }
    }

    fun create(uri: URI): PackageUri? {
      val authority = uri.authority ?: return null
      val path = uri.path ?: return null
      val versionAndChecksumPart =
        path.substringAfterLast('@', "").ifEmpty {
          return null
        }
      val checksumPart = versionAndChecksumPart.substringAfterLast("::", "")
      val checksums =
        if (checksumPart.contains(":")) {
          val (algName, value) = checksumPart.split(":")
          if (algName != "sha256") return null
          Checksums(value)
        } else null
      val versionStr = versionAndChecksumPart.substringBeforeLast("::")
      val version = Version.parseOrNull(versionStr) ?: return null
      return PackageUri(authority, path, version, checksums)
    }

    fun create(str: String): PackageUri? {
      val uri =
        try {
          URI(str)
        } catch (e: URISyntaxException) {
          return null
        }
      return create(uri)
    }
  }

  override fun toString(): String = "package://$authority$basePath@$version"

  fun toStringWithChecksum(): String =
    if (checksums != null) "$this::sha256:${checksums.sha256}" else this.toString()

  override fun hashCode(): Int {
    return Objects.hash(authority, path)
  }

  override fun equals(other: Any?): Boolean {
    if (other !is PackageUri) return false
    return authority == other.authority && path == other.path
  }

  private val lastSegmentName = path.substringAfterLast('/').substringBeforeLast("::")

  private val packageFilenameBase = "$authority$basePath@$version/$lastSegmentName"

  val relativeZipFiles =
    listOf(encodePath("package-2/$packageFilenameBase.zip"), "package-1/$packageFilenameBase.zip")

  val relativeMetadataFiles =
    listOf(encodePath("package-2/$packageFilenameBase.json"), "package-1/$packageFilenameBase.json")

  fun asPackageDependency(context: PklProject?): PackageDependency =
    PackageDependency(this, context, this.checksums)
}
