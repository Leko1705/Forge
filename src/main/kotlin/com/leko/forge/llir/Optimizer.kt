package com.leko.forge.llir

import com.leko.forge.context.Context


class Optimizer private constructor(context: Context) {

    companion object {
        fun instance(context: Context): Optimizer =
            context.getServices(Optimizer::class) { Optimizer(context) }
    }

    fun optimize(module: LLIRModule): LLIRModule {
        val phases = listOf<OptimizationPhase>()

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
