/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations

val buildInfo = project.extensions.getByType<BuildInfo>()

/** Downloads the sources jars of all runtime dependencies. */
tasks.register<ResolveSourcesJars>("resolveSourcesJars") {
  configuration.set(configurations.named("runtimeClasspath"))
  outputDir.set(layout.buildDirectory.dir("resolveSourcesJars"))
}

repositories {
  // Source archives of tagged GitHub releases, used to bundle the sources of native code.
  exclusiveContent {
    forRepository {
      ivy {
        name = "GitHub tag archives"
        url = uri("https://github.com/")
        patternLayout { artifact("[organisation]/[module]/archive/refs/tags/[revision].tar.gz") }
        metadataSources { artifact() }
      }
    }
    filter {
      includeGroup("openjdk")
      includeGroup("oracle")
      includeGroup("tree-sitter")
      includeGroup("apple")
    }
  }
}

// Source bundles contain the sources of everything that is compiled into the native executable of
// a target, including its dependencies, the JDK, SubstrateVM, and native libraries.
//
// The JDK, SubstrateVM, and Graal sources are derived from the lists of compiled classes in
// `build/compiled-classes` (download them with the `downloadCompiledClasses` task). Those lists are
// produced by native-image builds, and are uploaded by CI as `compiled-classes-*` artifacts. This
// way, the source bundles of all targets can be built from any machine.
//
// Sources are taken from the OpenJDK and Graal repositories at the tags matching the GraalVM that
// pkl-lsp is built with, because GraalVM doesn't ship all of the sources (or any C sources).
val openJdkSourcesTarball: Configuration = configurations.create("openJdkSourcesTarball")
val graalSourcesTarball: Configuration = configurations.create("graalSourcesTarball")
val treeSitterSourcesTarball: Configuration = configurations.create("treeSitterSourcesTarball")
val treeSitterPklSourcesTarball: Configuration =
  configurations.create("treeSitterPklSourcesTarball")

dependencies {
  val jdkVersion = buildInfo.libs.findVersion("graalVmJdkVersion").get().toString()
  val graalVmVersion = buildInfo.libs.findVersion("graalVm").get().toString()
  val treeSitterVersion = buildInfo.libs.findVersion("treeSitterRepo").get().toString()
  val treeSitterPklVersion = buildInfo.libs.findVersion("treeSitterPklRepo").get().toString()
  add("openJdkSourcesTarball", "openjdk:jdk${jdkVersion.substringBefore('.')}u:jdk-$jdkVersion-ga")
  add("graalSourcesTarball", "oracle:graal:vm-$graalVmVersion")
  add("treeSitterSourcesTarball", "tree-sitter:tree-sitter:$treeSitterVersion")
  add("treeSitterPklSourcesTarball", "apple:tree-sitter-pkl:$treeSitterPklVersion")
}

// pkl-lsp's own sources. Unlike the published sources jar, this excludes the compiled tree-sitter
// libraries, because `nativeLibDir` is a resources directory of the main source set.
val bundleSourcesJar =
  tasks.register<Jar>("bundleSourcesJar") {
    archiveClassifier = "sources"
    destinationDirectory = layout.buildDirectory.dir("tmp/sourceBundle")
    val mainSourceSet =
      project.extensions.getByType<JavaPluginExtension>().sourceSets.getByName("main")
    from(mainSourceSet.allSource) { exclude("NATIVE/**") }
  }

// Drops the `<repo>-<tag>/` root directory of a GitHub tag archive.
fun CopySpec.dropRootDirectory() {
  eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
}

// The tree-sitter libraries are compiled from source at build time (see `makeTreeSitter`).
val treeSitterSourcesJar =
  tasks.register<Zip>("treeSitterSourcesJar") {
    archiveFileName = "tree-sitter-sources.jar"
    destinationDirectory = layout.buildDirectory.dir("tmp/sourceBundle")
    includeEmptyDirs = false
    from(tarTree(resources.gzip(treeSitterSourcesTarball.singleFile))) {
      include("*/lib/include/**", "*/lib/src/**", "*/LICENSE*", "*/NOTICE*")
      dropRootDirectory()
    }
  }

val treeSitterPklSourcesJar =
  tasks.register<Zip>("treeSitterPklSourcesJar") {
    archiveFileName = "tree-sitter-pkl-sources.jar"
    destinationDirectory = layout.buildDirectory.dir("tmp/sourceBundle")
    includeEmptyDirs = false
    from(tarTree(resources.gzip(treeSitterPklSourcesTarball.singleFile))) {
      include("*/src/**", "*/grammar.js", "*/LICENSE*", "*/NOTICE*")
      dropRootDirectory()
    }
  }

// The JDK native libraries that native-image links into the image.
val openJdkNativeLibraries = listOf("java", "net", "nio", "zip", "extnet", "management_ext")

// Platform-specific directories in OpenJDK's `src/<module>/<platform>/`.
fun openJdkPlatforms(os: Target.OS): List<String> = buildList {
  add("share")
  if (!os.isWindows) add("unix")
  add(
    when {
      os.isMacOS -> "macosx"
      os.isWindows -> "windows"
      else -> "linux"
    }
  )
}

// The `substratevm/src/com.oracle.svm.native.*` projects that apply to the OS.
fun svmNativeProjects(os: Target.OS): List<String> = buildList {
  add("libchelper")
  when {
    os.isMacOS -> {
      add("darwin")
      add("jvm.posix")
    }
    os.isLinux -> {
      add("libcontainer")
      add("jvm.posix")
    }
    os.isWindows -> add("jvm.windows")
  }
}

val sourceBundleTasks =
  Target.entries
    // there is no native executable for Alpine
    .filter { !it.musl }
    .map { target ->
      val variant = target.name
      val outputDir = layout.buildDirectory.dir("tmp/sourceBundle/${target.targetName}")
      val compiledClassesFile =
        layout.buildDirectory.file("compiled-classes/${target.targetName}.txt")

      val nativeImageSourcesJar =
        tasks.register<NativeImageSourcesJar>("nativeImageSourcesJar$variant") {
          compiledClasses = compiledClassesFile
          openJdkTarball.from(openJdkSourcesTarball)
          graalTarball.from(graalSourcesTarball)
          openJdkPlatforms.addAll(openJdkPlatforms(target.os))
          outputJar = outputDir.map { it.file("native-image-sources.jar") }
        }

      val openJdkNativeSourcesJar =
        tasks.register<Zip>("openJdkNativeSourcesJar$variant") {
          archiveFileName = "openjdk-native-sources.jar"
          destinationDirectory = outputDir
          includeEmptyDirs = false

          from(tarTree(resources.gzip(openJdkSourcesTarball.singleFile))) {
            for (platform in openJdkPlatforms(target.os)) {
              include("*/src/*/$platform/native/include/**")
              for (library in openJdkNativeLibraries) {
                include("*/src/*/$platform/native/lib$library/**")
              }
            }
            dropRootDirectory()
          }
        }

      val graalNativeSourcesJar =
        tasks.register<Zip>("graalNativeSourcesJar$variant") {
          archiveFileName = "graal-native-sources.jar"
          destinationDirectory = outputDir
          includeEmptyDirs = false

          from(tarTree(resources.gzip(graalSourcesTarball.singleFile))) {
            for (svmProject in svmNativeProjects(target.os)) {
              include("*/substratevm/src/com.oracle.svm.native.$svmProject/**")
            }
            dropRootDirectory()
          }
        }

      tasks.register<BuildSourceBundle>("sourceBundle$variant") {
        group = "build"
        description = "Builds the source bundle of pkl-lsp for ${target.targetName}."
        inputJars.from(
          bundleSourcesJar,
          treeSitterSourcesJar,
          treeSitterPklSourcesJar,
          nativeImageSourcesJar,
          openJdkNativeSourcesJar,
          graalNativeSourcesJar,
        )
        // Of the dependencies, only include sources of classes that are compiled into the
        // executable of this target.
        filteredInputJars.from(
          tasks.named<ResolveSourcesJars>("resolveSourcesJars").map { fileTree(it.outputDir) }
        )
        compiledClasses = compiledClassesFile
        outputZip =
          layout.buildDirectory.file(
            "sourceBundle/${project.name}-${project.version}-${target.targetName}-sourcebundle.zip"
          )
      }
    }

// pkl-lsp has one source bundle per target.
tasks.register("sourceBundle") {
  group = "build"
  description = "Builds the source bundles of pkl-lsp for all targets."
  dependsOn(sourceBundleTasks)
}

/**
 * Downloads the lists of classes compiled into native executables, which CI uploads as
 * `compiled-classes-<os>-<arch>` artifacts, into `build/compiled-classes`.
 *
 * Requires the GitHub CLI (`gh`) to be installed and authenticated.
 */
abstract class DownloadCompiledClasses : DefaultTask() {
  @get:Option(option = "run-id", description = "ID of the GitHub Actions run to download from.")
  @get:Input
  abstract val runId: Property<String>

  @get:Option(option = "repo", description = "GitHub repository that the run belongs to.")
  @get:Input
  abstract val repo: Property<String>

  /** Name of the artifacts to download. */
  @get:Input abstract val artifactPattern: Property<String>

  /** The prefix of the file names inside of the artifacts, which are `<prefix>-<version>-*.txt`. */
  @get:Input abstract val fileNamePrefix: Property<String>

  @get:OutputDirectory abstract val destinationDir: DirectoryProperty

  @get:Inject protected abstract val execOperations: ExecOperations

  init {
    group = "build"
    description = "Downloads the compiled classes lists that CI produced into the build directory."
    repo.convention("apple/pkl-lsp")
    // always re-download; this task is meant to be run manually
    outputs.upToDateWhen { false }
  }

  @TaskAction
  @Suppress("unused")
  fun download() {
    if (!runId.isPresent) {
      throw GradleException("Specify the run to download from, e.g. `--run-id=<id>`")
    }
    val downloadDir = temporaryDir.resolve("download")
    downloadDir.deleteRecursively()
    downloadDir.mkdirs()

    execOperations.exec {
      commandLine(
        "gh",
        "run",
        "download",
        runId.get(),
        "--repo",
        repo.get(),
        "--pattern",
        artifactPattern.get(),
        "--dir",
        downloadDir.absolutePath,
      )
    }

    // Files are named `<prefix>-<version>-<target name>.txt`; keep only the target name.
    val targetNames = Target.entries.joinToString("|") { Regex.escape(it.targetName) }
    val fileName = Regex("^${Regex.escape(fileNamePrefix.get())}-.+?-($targetNames)\\.txt$")
    val downloaded =
      downloadDir
        .walkTopDown()
        .filter { it.isFile }
        .associate { file ->
          val target =
            fileName.matchEntire(file.name)?.groupValues?.get(1)
              ?: throw GradleException("Unexpected file in downloaded artifacts: $file")
          target to file
        }
    if (downloaded.isEmpty()) {
      throw GradleException("Run ${runId.get()} has no `${artifactPattern.get()}` artifacts")
    }

    val destination = destinationDir.get().asFile
    destination.mkdirs()
    // remove lists of targets that are no longer built
    destination.listFiles { f -> f.extension == "txt" }?.forEach { it.delete() }
    for ((target, file) in downloaded) {
      file.copyTo(destination.resolve("$target.txt"))
    }
    logger.lifecycle("Downloaded ${downloaded.keys.sorted()} into $destination")
  }
}

tasks.register<DownloadCompiledClasses>("downloadCompiledClasses") {
  artifactPattern = "compiled-classes-*"
  fileNamePrefix = project.name
  destinationDir = layout.buildDirectory.dir("compiled-classes")
}
