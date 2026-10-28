package com.leko.forge.utils

import com.leko.forge.context.Context

/**
 * Orchestrator — javac's `JavaCompiler#compile` analog. Drives the fixed phase
 * pipeline over one parsed root, stopping early if an earlier phase already
 * produced errors that would make later phases meaningless (e.g. no point
 * running completeness checking if node names themselves failed to resolve).
 */
class Version private constructor() {
    companion object {
        fun instance(context: Context): Version =
            context.getServices(Version::class) { Version() }
    }

    val name: String = "Forge 0.1.0"
}
