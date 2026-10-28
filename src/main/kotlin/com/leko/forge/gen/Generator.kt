package com.leko.forge.gen

import ForgeParser
import com.leko.forge.analysis.targetWrittenSuccessfully
import com.leko.forge.context.Context
import com.leko.forge.llir.LLIRModule
import com.leko.forge.utils.Options
import com.leko.forge.util.Log
import java.io.File

/**
 * Orchestrator — javac's `JavaCompiler#compile` analog. Drives the fixed phase
 * pipeline over one parsed root, stopping early if an earlier phase already
 * produced errors that would make later phases meaningless (e.g. no point
 * running completeness checking if node names themselves failed to resolve).
 */
class Generator private constructor(private val context: Context) {
    companion object {
        fun instance(context: Context): Generator =
            context.getServices(Generator::class) { Generator(context) }
    }

    private val targets = Targets.instance(context)

    private val options = Options.instance(context)

    private val log = Log.instance(context)

    fun generate(module: LLIRModule) {
        val targetName = options.get("-t")?.first() ?: "kotlin"
        val target = targets[targetName]!!
        val bytes = target.generate(module, context)

        val sourceName = options.get("-o")?.first() ?: module.sourceName ?: "out"

        val dir = options.get("-d")?.first()?.ifBlank { "." } ?: "."

        val outFile = File(dir, File(sourceName).nameWithoutExtension + target.extension)
        outFile.parentFile.mkdirs()
        outFile.writeBytes(bytes)
        log.diag(targetWrittenSuccessfully(null, targetName, outFile.absolutePath))
    }
}
