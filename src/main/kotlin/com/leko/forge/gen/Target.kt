package com.leko.forge.gen

import com.leko.forge.context.Context
import com.leko.forge.llir.LLIRModule

interface Target {

    val name: String

    val extension: String

    /** The name of the generated file without extension; Java needs the name of its class. */
    fun outputName(module: LLIRModule, base: String): String = base

    fun generate(module: LLIRModule, context: Context): ByteArray

}
