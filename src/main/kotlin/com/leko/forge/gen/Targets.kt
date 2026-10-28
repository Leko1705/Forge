package com.leko.forge.gen

import com.leko.forge.context.Context

/**
 * Orchestrator — javac's `JavaCompiler#compile` analog. Drives the fixed phase
 * pipeline over one parsed root, stopping early if an earlier phase already
 * produced errors that would make later phases meaningless (e.g. no point
 * running completeness checking if node names themselves failed to resolve).
 */
class Targets private constructor() {

    companion object {
        fun instance(context: Context): Targets =
            context.getServices(Targets::class) { Targets() }
    }

    private val _targets = mutableMapOf<String, Target>()

    init {
        register(KotlinTarget)
        // register(PythonTarget)
    }

    operator fun get(name: String): Target? = _targets[name]

    private fun register(target: Target) {
        _targets[target.name] = target
    }
}
