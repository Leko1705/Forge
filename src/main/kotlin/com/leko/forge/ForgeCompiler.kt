package com.leko.forge

import com.leko.forge.analysis.Analyzer
import com.leko.forge.context.Context
import com.leko.forge.gen.Generator
import com.leko.forge.llir.LLIR
import com.leko.forge.llir.Optimizer
import com.leko.forge.lr.ParserGen
import com.leko.forge.parse.mergeRoots
import com.leko.forge.parse.parseForge
import com.leko.forge.util.Log
import com.leko.forge.utils.Options
import com.leko.forge.utils.Version
import java.io.File

/**
 * Drives a compilation (javac's `JavaCompiler` analog):
 * parse → enter → member enter → attribute → check → generate parsers → LLIR → (optimize) → generate.
 * All files given on the command line form one program, so a language can be
 * spread over (and extended by) several files.
 */
class ForgeCompiler private constructor(private val context: Context) {
    companion object {
        fun instance(context: Context): ForgeCompiler =
            context.getServices(ForgeCompiler::class) { ForgeCompiler(context) }

        const val USAGE = """usage: forge [options] <file.forge>...

options:
  -d <dir>        output directory (default: .)
  -o <name>       name of the generated file (default: name of the first input file)
  -p <package>    package of the generated code
  -t <target>     target language (default: kotlin)
  --no-runtime    do not write ForgeRuntime.kt (e.g. when it is already part of the project)
  -opt            optimize the intermediate representation
  -v              print the version
  -h              print this help"""
    }

    private val options = Options.instance(context)
    private val log = Log.instance(context)

    /** Compiles according to the options; returns the generated files, or null on errors. */
    fun compile(): List<File>? {
        val files = options.unbound("-f", "--f").map { File(it) }
        if (files.isEmpty()) {
            println("error: no input files")
            println(USAGE)
            return null
        }
        files.firstOrNull { !it.isFile }?.let {
            println("error: file not found: $it")
            return null
        }

        val root = mergeRoots(files.map { it.parseForge(log) })
        if (log.hasErrors()) return finish(null)

        Analyzer.instance(context).analyze(root)
        if (log.hasErrors()) return finish(null)

        val parsers = ParserGen.instance(context).generate(root)
        if (log.hasErrors()) return finish(null)

        var module = LLIR.instance(context).transform(root, parsers)
        if (options.isSet("-opt") || options.isSet("--optimized")) {
            module = Optimizer.instance(context).optimize(module)
        }
        return finish(Generator.instance(context).generate(module))
    }

    private fun finish(result: List<File>?): List<File>? {
        log.report()
        log.summary()
        return result
    }

    val version: String get() = Version.instance(context).name
}
