package com.leko.forge.llir

import ForgeParser
import com.leko.forge.analysis.*
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.setSym
import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.toType
import com.leko.forge.util.Log


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
