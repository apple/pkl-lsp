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
package org.pkl.lsp.util

import java.util.*

object OS {
  private val osNameProperty
    get() = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: "UNKNOWN"

  private val osArchProperty
    get() = System.getProperty("os.arch")?.lowercase(Locale.ROOT) ?: "UNKNOWN"

  /** Same logic as `org.gradle.internal.os.OperatingSystem.forName` */
  val name
    get(): String {
      return when {
        osNameProperty.contains("mac os x") ||
          osNameProperty.contains("darwin") ||
          osNameProperty.contains("osx") -> "macos"
        osNameProperty.contains("linux") -> "linux"
        osNameProperty.contains("windows") -> "windows"
        else -> throw RuntimeException("OS $osNameProperty is not supported")
      }
    }

  /** Same logic as `org.gradle.internal.os.OperatingSystem#arch` */
  val arch: String
    get() {
      return when (osArchProperty) {
        "x86" -> "i386"
        "x86_64" -> "amd64"
        "powerpc" -> "ppc"
        else -> osArchProperty
      }
    }
}
