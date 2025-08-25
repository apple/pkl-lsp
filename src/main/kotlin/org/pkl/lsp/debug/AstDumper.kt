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
package org.pkl.lsp.debug

import java.net.URI
import java.nio.file.Path
import kotlin.io.path.*
import org.pkl.lsp.Project
import org.pkl.lsp.PklLspServer
import org.pkl.lsp.VirtualFileManager
import org.pkl.lsp.ast.*
import org.pkl.lsp.packages.dto.PklProject

class AstDumper {
  
  fun dumpFile(filePath: Path) {
    println("=".repeat(80))
    println("AST DUMP FOR: $filePath")
    println("=".repeat(80))
    
    val project = createProject(filePath)
    
    // Load virtual file AFTER project sync to ensure proper project association
    val virtualFile = project.virtualFileManager.get(filePath.toUri())
    
    if (virtualFile == null) {
      println("ERROR: Could not load file $filePath")
      return
    }
    
    // Print file info
    println("FILE INFO:")
    println("  Path: ${virtualFile.path}")
    println("  URI: ${virtualFile.uri}")
    println("  PklProject: ${virtualFile.pklProject}")
    println("  PklProjectDir: ${virtualFile.pklProjectDir}")
    if (virtualFile.pklProject != null) {
      val proj = virtualFile.pklProject!!
      println("  Project URI: ${proj.metadata.projectFileUri}")
      println("  Package URI: ${proj.metadata.packageUri}")
      println("  Dependencies: ${proj.metadata.declaredDependencies}")
    }
    
    // Test package URI computation
    println("\nPACKAGE URI TESTS:")
    try {
      // Try to get the module first to test package URI computation
      val moduleFuture = virtualFile.getModule()
      val module = moduleFuture.get()
      if (module != null) {
        println("  Module shortDisplayName: ${module.shortDisplayName}")
        
        // Check if module has any package URI information
        println("  Module URI: ${module.uri}")
        println("  Module moduleName: ${module.moduleName}")
        println("  Module virtualFile URI: ${module.virtualFile.uri}")
        println("  Module isAmend: ${module.isAmend}")
        
        // Check if the virtual file has different URI info
        println("  VirtualFile path vs uri:")
        println("    Path: ${module.virtualFile.path}")
        println("    URI:  ${module.virtualFile.uri}")
        
        // Check if there are imports that might affect package computation
        println("  Imports: ${module.imports.size}")
        module.imports.forEachIndexed { i, import ->
          println("    Import $i: ${import.moduleUri?.stringConstant?.text}")
        }
      }
    } catch (e: Exception) {
      println("  Error testing package URIs: ${e.message}")
    }
    println()
    
    // Parse and dump AST
    val moduleFuture = virtualFile.getModule()
    try {
      val module = moduleFuture.get()
      if (module != null) {
        dumpNode(module, 0, virtualFile.pklProject)
      } else {
        println("ERROR: Could not parse module")
      }
    } catch (e: Exception) {
      println("ERROR: Exception getting module: ${e.message}")
    }
    
    println("=".repeat(80))
    println()
  }
  
  private fun dumpNode(node: PklNode, indent: Int, context: org.pkl.lsp.packages.dto.PklProject?) {
    val prefix = "  ".repeat(indent)
    val nodeType = node::class.simpleName
    
    print("$prefix$nodeType")
    
    // Add useful node-specific info
    when (node) {
      is IdentifierOwner -> {
        print(" [${node.identifier?.text ?: "null"}]")
      }
      is PklStringConstant -> {
        print(" [\"${node.text}\"]")
      }
      is PklModule -> {
        print(" [${node.moduleName}]")
        print(" pklProject=${node.containingFile.pklProject != null}")
      }
    }
    
    // Try to resolve if it's a reference
    if (node is PklReference) {
      try {
        val resolved = node.resolve(context)
        if (resolved != null) {
          val resolvedFile = getContainingFile(resolved)
          val resolvedProject = resolvedFile?.containingFile?.pklProject
          print(" -> resolves to [${resolved.enclosingModule?.moduleName}] ${resolved::class.simpleName}")
          if (resolved is IdentifierOwner) {
            print("[${resolved.identifier?.text}]")
          }
          if (resolvedProject != null) {
            print(" in project ${resolvedProject.metadata.projectFileUri}")
          } else {
            print(" (no project)")
          }
        } else {
          print(" -> unresolved")
        }
      } catch (e: Exception) {
        print(" -> resolve error: ${e.message}")
      }
    }
    
    println()
    
    // Recursively dump children
    node.children.forEach { child ->
      dumpNode(child, indent + 1, context)
    }
  }
  
  private fun getContainingFile(node: PklNode): PklModule? {
    var current: PklNode? = node
    while (current != null) {
      if (current is PklModule) {
        return current
      }
      current = current.parent
    }
    return null
  }
  
  private fun createProject(filePath: Path): Project {
    // Use the common function that properly handles file-to-project association
    val projectWithServer = org.pkl.lsp.cli.ScipCommand.createProjectForFiles(listOf(filePath), verbose = true)
    val project = projectWithServer.project
    
    // Debug: Check what projects were actually loaded
    try {
      val fsFile = project.virtualFileManager.getFsFile(filePath.toUri())!!
      val pklProjectForFile = project.pklProjectManager.getPklProject(fsFile)
      println("Debug: PklProject from manager: $pklProjectForFile")
      
      // Check if any PklProject files were discovered in the workspace
      val workspaceFolder = (filePath.parent ?: filePath).toAbsolutePath()
      println("Debug: Workspace folder: $workspaceFolder")
      println("Debug: Current working directory: ${System.getProperty("user.dir")}")
      println("Debug: FilePath absolute: ${filePath.toAbsolutePath()}")
      
      // Try to manually check if PklProject file exists
      val possiblePklProjectFile = workspaceFolder.resolve("PklProject")
      println("Debug: PklProject file exists: ${possiblePklProjectFile.exists()}")
      println("Debug: PklProject file absolute path: ${possiblePklProjectFile.toAbsolutePath()}")
      
    } catch (e: Exception) {
      println("Warning: Failed to check PklProject files: ${e.message}")
      e.printStackTrace()
    }
    
    return project
  }
}

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("Usage: AstDumper <file1.pkl> [file2.pkl] ...")
    return
  }
  
  val dumper = AstDumper()
  
  args.forEach { arg ->
    val path = Path.of(arg)
    if (path.exists()) {
      dumper.dumpFile(path)
    } else {
      println("ERROR: File does not exist: $path")
    }
  }
}
