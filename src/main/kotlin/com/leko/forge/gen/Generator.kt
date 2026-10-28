package com.leko.forge.gen

import com.leko.forge.analysis.targetWrittenSuccessfully
import com.leko.forge.context.Context
import com.leko.forge.llir.LLIRModule
import com.leko.forge.utils.Options
import com.leko.forge.util.Log
import java.io.File

/** Writes the files of the selected target (`-t`, default kotlin) into the output directory (`-d`). */
class Generator private constructor(private val context: Context) {
    companion object {
        fun instance(context: Context): Generator =
            context.getServices(Generator::class) { Generator(context) }
    }

    private val targets = Targets.instance(context)

    private val options = Options.instance(context)

    private val log = Log.instance(context)

    fun generate(module: LLIRModule): List<File> {
        val targetName = options.get("-t")?.firstOrNull() ?: "kotlin"
        val target = targets[targetName] ?: throw IllegalArgumentException("unknown target '$targetName'")

        val sourceName = options.get("-o")?.firstOrNull() ?: module.sourceName ?: "out"
        val dir = options.get("-d")?.firstOrNull()?.ifBlank { "." } ?: "."

        return target.generate(module, File(sourceName).nameWithoutExtension, context).map { file ->
            val outFile = File(dir, file.name)
            outFile.absoluteFile.parentFile.mkdirs()
            outFile.writeBytes(file.content)
            log.diag(targetWrittenSuccessfully(null, targetName, outFile.path))
            outFile
        }
    }
}
