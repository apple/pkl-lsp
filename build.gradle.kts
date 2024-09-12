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
plugins {
  application
  alias(libs.plugins.kotlin)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.shadow)
  alias(libs.plugins.spotless)
}

repositories { mavenCentral() }

java {
  sourceCompatibility = JavaVersion.VERSION_22
  toolchain { languageVersion = JavaLanguageVersion.of(22) }
}

val pklCli: Configuration by configurations.creating

dependencies {
  implementation(kotlin("reflect"))
  implementation(libs.antlr)
  implementation(libs.clikt)
  implementation(libs.pklCore)
  implementation(libs.lsp4j)
  implementation(libs.kotlinxSerializationJson)
  implementation(libs.jtreesitter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation(libs.assertJ)
  testImplementation(libs.junit.jupiter)
  pklCli(libs.pklCli)
}

val configurePklCliExecutable by
  tasks.registering { doLast { pklCli.singleFile.setExecutable(true) } }

tasks.jar {
  manifest {
    attributes +=
      mapOf("Main-Class" to "org.pkl.lsp.cli.Main", "Enable-Native-Access" to "ALL-UNNAMED")
  }
}

application { mainClass.set("org.pkl.lsp.cli.Main") }

tasks.test {
  dependsOn(configurePklCliExecutable)
  systemProperties["pklExecutable"] = pklCli.singleFile.absolutePath
  useJUnitPlatform()
  System.getProperty("testReportsDir")?.let { reportsDir ->
    reports.junitXml.outputLocation.set(file(reportsDir).resolve(project.name).resolve(name))
  }
}

tasks.shadowJar { archiveFileName.set("pkl-lsp") }

val javaExecutable by
  tasks.registering(ExecutableJar::class) {
    inJar.set(tasks.shadowJar.flatMap { it.archiveFile })
    outJar.set(layout.buildDirectory.file("executable/pkl-lsp"))

    // uncomment for debugging
    // jvmArgs.addAll("-ea", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
  }

private val licenseHeader =
  """
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
"""
    .trimIndent()

spotless {
  kotlinGradle {
    ktfmt(libs.versions.ktfmt.get()).googleStyle()
    targetExclude("**/generated/**", "**/build/**")
    licenseHeader(licenseHeader, "([a-zA-Z]|@file|//)")
    target("*.kts", "buildSrc/**/*.kts")
  }
  kotlin {
    ktfmt(libs.versions.ktfmt.get()).googleStyle()
    licenseHeader(licenseHeader)
  }
}

/**
 * Builds a self-contained Pkl LSP CLI Jar that is directly executable on *nix and executable with
 * `java -jar` on Windows.
 *
 * For direct execution, the `java` command must be on the PATH.
 *
 * https://skife.org/java/unix/2011/06/20/really_executable_jars.html
 */
abstract class ExecutableJar : DefaultTask() {
  @get:InputFile abstract val inJar: RegularFileProperty

  @get:OutputFile abstract val outJar: RegularFileProperty

  @get:Input abstract val jvmArgs: ListProperty<String>

  @TaskAction
  fun buildJar() {
    val inFile = inJar.get().asFile
    val outFile = outJar.get().asFile
    val escapedJvmArgs = jvmArgs.get().joinToString(separator = " ") { "\"$it\"" }
    val startScript =
      """
            #!/bin/sh
            exec java $escapedJvmArgs -jar $0 "$@"
            """
        .trimIndent() + "\n\n\n"
    outFile.outputStream().use { outStream ->
      startScript.byteInputStream().use { it.copyTo(outStream) }
      inFile.inputStream().use { it.copyTo(outStream) }
    }

    // chmod a+x
    outFile.setExecutable(true, false)
  }
}
