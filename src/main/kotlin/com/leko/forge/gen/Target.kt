package com.leko.forge.gen

import com.leko.forge.context.Context
import com.leko.forge.llir.LLIRModule

class GeneratedFile(val name: String, val content: ByteArray)

interface Target {

    val name: String

    /** Generates the files of the compiler described by [module]; [baseName] is the name of the main file without extension. */
    fun generate(module: LLIRModule, baseName: String, context: Context): List<GeneratedFile>

}
