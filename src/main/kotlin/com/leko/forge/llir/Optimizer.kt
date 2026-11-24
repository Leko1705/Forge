package com.leko.forge.llir

import com.leko.forge.context.Context
import com.leko.forge.utils.Options

/**
 * Optimizes a module before code is generated (`-opt`): constant folding, then dead code elimination, until
 * neither changes anything. `-keep name...` keeps functions (`f` or `Receiver.f`), attributes, graphs and parsers
 * (`P` or a rule `P.r`) of imported files that the compiled file does not use itself.
 */
class Optimizer private constructor(private val context: Context) {

    companion object {
        fun instance(context: Context): Optimizer =
            context.getServices(Optimizer::class) { Optimizer(context) }
    }

    fun optimize(module: LLIRModule): LLIRModule {
        val keep = Options.instance(context).get("-keep").orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            // a declaration of a module by its qualified name: jvm::ClassFile
            .map { it.replace("::", com.leko.forge.types.MODULE_SEPARATOR) }.toSet()
        val phases = listOf(ConstantFolding, DeadCodeElimination(keep))

        var module = module
        var complete = false
        while (!complete) {
            complete = true
            for (phase in phases) {
                val (optimized, modified) = phase.apply(module)
                module = optimized
                complete = complete && !modified
            }
        }
        return module
    }
}
