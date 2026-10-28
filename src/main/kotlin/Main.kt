import com.leko.forge.ForgeCompiler
import com.leko.forge.context.Context
import com.leko.forge.utils.Options
import kotlin.system.exitProcess


fun main(args: Array<String>) {
    val context = Context()
    Options.instance(context).set(args)
    val compiler = ForgeCompiler.instance(context)

    if ("-h" in args || "--help" in args) {
        println(ForgeCompiler.USAGE)
        return
    }
    if ("-v" in args || "--version" in args) {
        println(compiler.version)
        return
    }

    val generated = compiler.compile()
    exitProcess(if (generated == null) 1 else 0)
}
