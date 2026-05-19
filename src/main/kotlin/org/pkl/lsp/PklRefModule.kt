package org.pkl.lsp

import org.pkl.lsp.ast.PklClass
import org.pkl.lsp.ast.PklModule
import org.pkl.lsp.type.Type

class PklRefModule(project: Project): Component(project) {
    val module: PklModule?
        get() = project.stdlib.ref?.getModule()?.get()

    val types: Map<String, Type> = buildMap {
        for (member in module?.members ?: emptyList()) {
            if (member is PklClass) {
                put(member.name, Type.Class.create(member))
            }
        }
    }

    // Will be `null` for versions < 0.32
    val referenceType: Type.Reference? by lazy { types["Reference"] as? Type.Reference }
}
