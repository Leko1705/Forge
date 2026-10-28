package com.leko.forge.gen

import com.leko.forge.context.Context
import com.leko.forge.llir.LLIRModule

interface Target {

    val name: String

    val extension: String

    fun generate(module: LLIRModule, context: Context): ByteArray

}