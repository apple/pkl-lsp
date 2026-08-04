/*
 * Copyright © 2025-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.kotlin.dsl.withNormalizer
import org.gradle.process.ExecOperations
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

enum class GraalVmArchitecture {
  AMD64,
  AARCH64,
}

abstract class NativeImageBuildService : BuildService<BuildServiceParameters.None>

abstract class NativeImageBuild : DefaultTask() {
  @get:Input abstract val imageName: Property<String>

  @get:Input abstract val extraNativeImageArgs: ListProperty<String>

  @get:Input abstract val arch: Property<GraalVmArchitecture>

  @get:Input abstract val mainClass: Property<String>

  @get:InputFiles abstract val classpath: ConfigurableFileCollection

  private val outputDir = project.layout.buildDirectory.dir("executable")

  @get:OutputFile val outputFile = outputDir.flatMap { it.file(imageName) }

  @get:Inject protected abstract val execOperations: ExecOperations

  private val graalVm: Provider<BuildInfo.GraalVm> = arch.map { a ->
    when (a) {
      GraalVmArchitecture.AMD64 -> buildInfo.graalVmAmd64
      GraalVmArchitecture.AARCH64 -> buildInfo.graalVmAarch64
    }
  }

  private val buildInfo: BuildInfo = project.extensions.getByType(BuildInfo::class.java)

  private val nativeImageCommandName =
    if (buildInfo.os.isWindows) "native-image.cmd" else "native-image"

  private val nativeImageExecutable = graalVm.map { it.baseDir.resolve("bin/${nativeImageCommandName}") }

  private val extraArgsFromProperties by lazy {
    System.getProperties()
      .filter { it.key.toString().startsWith("pkl.native") }
      .map { "${it.key}=${it.value}".substring("pkl.native".length) }
  }

  private val buildService =
    project.gradle.sharedServices.registerIfAbsent(
      "nativeImageBuildService",
      NativeImageBuildService::class,
    ) {
      maxParallelUsages.set(1)
    }

  init {
    // ensure native-image builds run in serial (prevent consuming all host CPU resources)
    usesService(buildService)

    group = "build"

    inputs
      .files(classpath)
      .withPropertyName("runtimeClasspath")
      .withNormalizer(ClasspathNormalizer::class)
    inputs
      .files(nativeImageExecutable)
      .withPropertyName("graalVmNativeImage")
      .withPathSensitivity(PathSensitivity.ABSOLUTE)
  }

  private fun createClasspathArgFile(): Path {
    val classpathArgFileContents = buildString {
      append("--class-path ")
      // native-image rejects non-existing class path entries -> filter
      val pathInput = classpath.filter { it.exists() }
      append(pathInput.asPath)
    }
    val buildDirectory = project.layout.buildDirectory.get().asFile.toPath()
    val tmpFile = buildDirectory.resolve("tmp/nativeImageBuild/${name}/args.txt")
    tmpFile.createParentDirectories()
    tmpFile.writeText(classpathArgFileContents)
    return tmpFile
  }

  @TaskAction
  protected fun run() {
    val argFile = createClasspathArgFile()
    val execResult = execOperations.exec {
      executable = nativeImageExecutable.get().absolutePath
      workingDir(outputDir)

      args = buildList {
        // must be emitted before any experimental options are used
        add("-H:+UnlockExperimentalVMOptions")
        // required for treesitter parsing
        add("-H:+ForeignAPISupport")
        add("-H:+SharedArenaSupport")
        add("--enable-native-access=ALL-UNNAMED")
        add("--no-fallback")
        // tree-sitter native libraries bundled as resources
        add("-H:IncludeResources=NATIVE/.*")
        // pkl stdlib, error messages, release properties, etc.
        add("-H:IncludeResources=org/pkl/.*")
        // service loader descriptors (e.g. jtreesitter NativeLibraryLookup)
        add("-H:IncludeResources=META-INF/services/.*")
        // error message resource bundle (loaded via ResourceBundle.getBundle)
        add("-H:IncludeResourceBundles=org.pkl.lsp.errorMessages")
        add("-H:Class=${mainClass.get()}")
        add("-o")
        add(imageName.get())
        add("--enable-url-protocols=http,https")
        add("-H:+ReportExceptionStackTraces")
        // disable automatic support for JVM CLI options
        add("-H:-ParseRuntimeOptions")
        if (!buildInfo.isReleaseBuild) {
          // quick build mode: 40% faster compilation, ~20% smaller executable
          add("-Ob")
        }
        if (buildInfo.isNativeArch) {
          add("-march=native")
        } else {
          add("-march=compatibility")
        }
        // add classpath args via argfile to avoid "The command line is too long" on Windows
        add("@${argFile.absolutePathString()}")
        // limit CPU usage on non-CI macOS
        val processors =
          Runtime.getRuntime().availableProcessors() /
            if (buildInfo.os.isMacOsX && !buildInfo.isCiBuild) 4 else 1
        add("-J-XX:ActiveProcessorCount=${processors}")
        // Pass through all `HOMEBREW_` prefixed environment variables
        addAll(environment.keys.filter { it.startsWith("HOMEBREW_") }.map { "-E$it" })
        addAll(extraNativeImageArgs.get())
        addAll(extraArgsFromProperties)
      }
    }
  }
}
