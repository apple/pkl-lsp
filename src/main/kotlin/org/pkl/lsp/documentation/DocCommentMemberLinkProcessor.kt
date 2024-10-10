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
package org.pkl.lsp.documentation

import org.pkl.lsp.ast.*

/**
 * Resolve
 * [member links](https://pkl-lang.org/main/current/language-reference/index.html#member-links), and
 * turn them into editor links.
 */
object DocCommentMemberLinkProcessor {
  private val linkRegex =
    Regex(
      """
    (?x)            # turn on extended mode (newlines are ignored)
    \[(.+?)]        # '[', followed by non-newline characters (1st capture), followed by ']'
    (?!\()          # not followed by '('
    (?:             # optionally followed by
        \[(.+?)]    # '[', non-newline characters (2nd capture), followed by ']'
    )?
    """
        .trimIndent()
    )

  private val keywords = setOf("null", "true", "false", "this", "unknown", "nothing")

  fun process(docComments: String, ownerNode: PklDocCommentOwner): String {
    return docComments.replace(linkRegex) { match ->
      val (memberLink, customLinkText) =
        if (match.groups[2] == null) {
          match.groups[1]!!.value to null
        } else {
          match.groups[2]!!.value to match.groups[1]!!.value
        }
      val linkText = customLinkText ?: memberLink
      if (memberLink in keywords) {
        "`$linkText`"
      } else {
        when (val resolved = resolveDocLink(memberLink, ownerNode)) {
          is PklNode -> {
            buildString {
              append("[`$linkText`](")
              append(resolved.getLocationUri(forDocs = true))
              append(")")
            }
          }
          else -> {
            "`$memberLink`"
          }
        }
      }
    }
  }

  private fun resolveDocLink(text: String, ownerNode: PklNode): PklNode? {
    var currentNode: PklNode? = ownerNode
    val parts = text.split('.')
    if (parts.isEmpty()) return null
    val first = parts[0]
    currentNode =
      when {
        first.endsWith("()") -> currentNode?.resolveMethod(first.dropLast(2))
        else -> currentNode?.resolveVariable(first) ?: return null
      }
    for (i in 1..parts.lastIndex) {
      val part = parts[i]
      currentNode =
        when {
          part.endsWith("()") -> currentNode?.getMethod(part.dropLast(2)) ?: return null
          else -> currentNode?.getProperty(part) ?: return null
        }
    }
    return currentNode
  }

  private fun PklNode.resolveMethod(methodName: String): PklNode? {
    val context = containingFile.pklProject
    return when (this) {
      is PklClass -> cache(context).methods[methodName] ?: parent?.resolveMethod(methodName)
      is PklModule -> cache(context).methods[methodName]
      else -> parent?.resolveMethod(methodName)
    }
  }

  private fun PklNode.getMethod(methodName: String): PklNode? {
    val context = containingFile.pklProject
    return when (this) {
      is PklClass -> cache(context).methods[methodName]
      is PklModule -> cache(context).methods[methodName]
      else -> null
    }
  }

  private fun PklNode.resolveVariable(variableName: String): PklNode? {
    val context = containingFile.pklProject
    return when (this) {
      is PklClass ->
        typeParameterList?.resolveVariable(variableName)
          ?: cache(context).properties[variableName]
          ?: parent?.resolveVariable(variableName)
      is PklModule ->
        when {
          variableName == "module" -> this
          else -> {
            imports
              .find { it.memberName == variableName }
              ?.let { import ->
                if (import.isGlob) import else import.resolveModules(context).firstOrNull()
              }
              ?: cache(context).typeDefsAndProperties[variableName]
              ?: project.pklBaseModule.module.cache(null).typeDefsAndProperties[variableName]
          }
        }
      is PklTypeParameterList -> typeParameters.find { it.name == variableName }
      is PklParameterList -> elements.find { it.identifier?.text == variableName }
      is PklMethod ->
        methodHeader.typeParameterList?.resolveVariable(variableName)
          ?: methodHeader.parameterList?.resolveVariable(variableName)
          ?: parent?.resolveVariable(variableName)
      else -> parent?.resolveVariable(variableName)
    }
  }

  private fun PklNode.getProperty(propertyName: String): PklNode? {
    val context = containingFile.pklProject
    return when (this) {
      is PklClass -> cache(context).properties[propertyName] ?: parent?.getProperty(propertyName)
      is PklModule -> cache(context).typeDefsAndProperties[propertyName]
      is PklTypeAlias -> enclosingModule?.getProperty(propertyName)
      else -> null
    }
  }
}
