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
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

val OperatingSystem.canonicalName get() = when {
  isMacOsX -> "macos"
  isWindows -> "windows"
  isLinux -> "linux"
  else -> throw RuntimeException("Unsupported OS: $name")
}

fun Project.runCommand(workingDir: File, command: List<String>): String {
  val execOps = serviceOf<ExecOperations>()
  val errorStream = ByteArrayOutputStream()
  val outputStream = ByteArrayOutputStream()
  val result =
    execOps.exec {
      this.workingDir = workingDir
      errorOutput = errorStream
      standardOutput = outputStream
      commandLine(command)
      isIgnoreExitValue = true
    }
  val errorOutput = errorStream.toString(StandardCharsets.UTF_8)
  val standardOutput = outputStream.toString(StandardCharsets.UTF_8)
  if (result.exitValue != 0) {
    throw GradleException(
      """
        Process '${command.joinToString(" ")}' exited with non-zero exit value ${result.exitValue}

        Stderr:
        $errorOutput

        Stdout:
        $standardOutput
      """
        .trimIndent()
    )
  }
  return outputStream.toString(StandardCharsets.UTF_8)
}
