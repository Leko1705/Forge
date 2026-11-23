import com.leko.forge.context.Context
import com.leko.forge.input.ArgumentParser
import com.leko.forge.parse.ImportException
import com.leko.forge.parse.parseForge
import com.leko.forge.analysis.Analyzer
import com.leko.forge.gen.Generator
import com.leko.forge.llir.LLIR
import com.leko.forge.llir.Optimizer
import com.leko.forge.utils.Options
import com.leko.forge.util.Log
import java.io.File
import kotlin.system.exitProcess

/**
 * `forge -f file.forge... [-t kotlin|java|python|cpp|rust|ocaml] [-d outDir] [-o outName] [-p package] [-opt]`
 */
fun main(args: Array<String>) {
    val argParser = ArgumentParser(args)

    val files = argParser.unbound("-f", "--f").map { File(it) }

    if (files.isEmpty()) {
        println("ERROR: Missing files")
        println("usage: forge -f file.forge... [-t kotlin|java|python|cpp|rust|ocaml] [-d outDir] [-o outName] [-p package] [-opt]")
        exitProcess(1)
    }

    var failed = false
    for (file in files) {
        // every file is compiled on its own
        val context = Context()
        Options.instance(context).set(args)
        val options = Options.instance(context)
        val log = Log.instance(context)

        // a file and the files it imports form one program
        val ast = try {
            file.parseForge()
        } catch (e: ImportException) {
            println("ERROR: " + e.message)
            failed = true
            continue
        }

        Analyzer.instance(context).analyze(ast)
        if (log.hasErrors()) {
            failed = true
            continue
        }

        var module = LLIR.instance(context).transform(ast)
        if (options.isSet("-opt") || options.isSet("--optimized")) {
            module = Optimizer.instance(context).optimize(module)
        }

        try {
            Generator.instance(context).generate(module)
        } catch (e: IllegalArgumentException) {
            println("ERROR: " + e.message)
            failed = true
            continue
        } catch (e: com.leko.forge.gen.UnsupportedRegexException) {
            println("ERROR: " + e.message)
            failed = true
            continue
        }
        log.diagnostics.lastOrNull()?.let { println(it.message) }
    }
    if (failed) exitProcess(1)
}
