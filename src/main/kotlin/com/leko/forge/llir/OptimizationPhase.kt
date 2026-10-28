package com.leko.forge.llir

abstract class OptimizationPhase(val tools: Tools) {

    protected fun Value.canonicalized(): Value = tools.canonicalize(this)

    abstract fun apply(module: LLIRModule): Pair<LLIRModule, Boolean>

}
