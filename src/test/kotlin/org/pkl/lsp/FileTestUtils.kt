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
package org.pkl.lsp

import java.nio.file.Path
import kotlin.io.path.exists

object FileTestUtils {
  val currentWorkingDir: Path
    get() = Path.of(System.getProperty("user.dir"))

  val rootProjectDir: Path by lazy {
    val workingDir = currentWorkingDir
    workingDir.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: workingDir.parent.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: workingDir.parent.parent.takeIf { it.resolve("settings.gradle.kts").exists() }
      ?: throw AssertionError("Failed to locate root project directory.")
  }
}
