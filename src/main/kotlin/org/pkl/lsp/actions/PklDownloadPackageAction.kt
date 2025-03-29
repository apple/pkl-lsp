/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.lsp.actions

import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.CodeActionKind
import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.Project
import org.pkl.lsp.packages.dto.PackageUri

class PklDownloadPackageAction(project: Project, packageUri: PackageUri) :
  PklCommandCodeAction("pkl.downloadPackage", listOf(packageUri.toString())) {
  override val title: String = "download package `${packageUri}"

  override val kind: String = CodeActionKind.QuickFix

  override val disabled: CodeActionDisabled? =
    if (project.pklCli.isUnavailable)
      CodeActionDisabled(ErrorMessages.create("pklCliNotConfigured"))
    else null
}
