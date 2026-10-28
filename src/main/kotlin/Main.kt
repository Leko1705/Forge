import com.leko.forge.context.Context
import com.leko.forge.input.ArgumentParser
import com.leko.forge.parse.parseForge
import com.leko.forge.analysis.Analyzer
import com.leko.forge.gen.Generator
import com.leko.forge.llir.LLIR
import com.leko.forge.llir.Optimizer
import com.leko.forge.utils.Options
import com.leko.forge.util.Log
import java.io.File
import kotlin.reflect.KClass
import kotlin.system.exitProcess


fun main(args: Array<String>) {

    val args = arrayOf("-f", "/home/kali/IdeaProjects/Forge/new.forge", "-opt")

    val argParser = ArgumentParser(args)

    val files = argParser.unbound("-f", "--f").map { File(it) }

    if (files.isEmpty()) {
        println("ERROR: Mising files")
        exitProcess(1)
    }

    val context = Context()

    val options = Options.instance(context)
    options.set(args)

    val log = Log.instance(context)

    for (file in files) {
        val ast = file.parseForge()

        val analyzer = Analyzer.instance(context)
        val generator = Generator.instance(context)
        val llir = LLIR.instance(context)

        analyzer.analyze(ast)
        if (log.hasErrors()) exitProcess(1)

        var module = llir.transform(ast)
        if (options.isSet("-opt") || options.isSet("--optimized")) {
            module = Optimizer.instance(context).optimize(module)
        }

        generator.generate(module)
    }

}
