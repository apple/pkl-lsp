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
@file:JvmName("Main")

package org.pkl.lsp.reflectionmetadatagenerator

import io.github.classgraph.ClassGraph
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
  require(args.size == 2) { "Usage: <outputFile> <inputFile>" }
  val outputFile = Path.of(args[0]).also { it.createParentDirectories() }
  val inputFile = Path.of(args[1])
  require(inputFile.exists()) {
    "Reachability metadata doesn't exist; should call `./gradlew generateReachabilityMetadataUsingTracingAgent before running this task"
  }

  val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
  }

  val reachabilityMetadata = json.parseToJsonElement(inputFile.readText()) as JsonObject

  val reflectionMetadata =
    (reachabilityMetadata["reflection"] as JsonArray)
      .filter { elem ->
        elem as JsonObject
        (elem["type"] as? JsonPrimitive)?.content?.startsWith("org.eclipse.lsp4j") != true
      }
      .toMutableList()

  ClassGraph().acceptPackages("org.eclipse.lsp4j").enableAllInfo().scan().use { scanResult ->
    for (classInfo in scanResult.allClasses) {
      val metadata = buildJsonObject {
        put("type", classInfo.name)
        put("allPublicConstructors", true)
        put("allPublicFields", true)
        put("allDeclaredFields", true)
        put("allDeclaredConstructors", true)
      }
      reflectionMetadata.add(metadata)
    }
  }

  val jsonRpcAnnotations =
    listOf(
      "org.eclipse.lsp4j.jsonrpc.services.JsonRequest",
      "org.eclipse.lsp4j.jsonrpc.services.JsonNotification",
    )

  // Collect all jsonrpc-annotated methods in `org.pkl.lsp` (including nested packages and classes).
  ClassGraph().acceptPackages("org.pkl.lsp").enableAllInfo().scan().use { scanResult ->
    val classes =
      jsonRpcAnnotations
        .flatMap { scanResult.getClassesWithMethodAnnotation(it) }
        .distinctBy { it.name }
    for (classInfo in classes) {
      val methods =
        classInfo.declaredMethodInfo
          .filter { method -> jsonRpcAnnotations.any { method.hasAnnotation(it) } }
          .map { method ->
            buildJsonObject {
              put("name", method.name)
              putJsonArray("parameterTypes") {
                for (param in method.parameterInfo) {
                  add(param.typeDescriptor.toString())
                }
              }
            }
          }
      reflectionMetadata.add(
        buildJsonObject {
          put("type", classInfo.name)
          put("allPublicConstructors", true)
          put("allPublicFields", true)
          put("allDeclaredFields", true)
          put("allDeclaredConstructors", true)
          put("methods", JsonArray(methods))
        }
      )
    }
  }

  val updatedJson = reachabilityMetadata.plus("reflection" to JsonArray(reflectionMetadata))
  val jsStr = json.encodeToString(updatedJson)
  outputFile.writeText(jsStr)
}
