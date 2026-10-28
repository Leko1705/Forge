package com.leko.forge.utils

import com.leko.forge.context.Context
import com.leko.forge.input.ArgumentParser

/**
 * Orchestrator — javac's `JavaCompiler#compile` analog. Drives the fixed phase
 * pipeline over one parsed root, stopping early if an earlier phase already
 * produced errors that would make later phases meaningless (e.g. no point
 * running completeness checking if node names themselves failed to resolve).
 */
class Options private constructor() {
    companion object {
        fun instance(context: Context): Options =
            context.getServices(Options::class) { Options() }
    }

    lateinit var argparse: ArgumentParser

    fun set(args: Array<String>) {
        argparse = ArgumentParser(args)
    }

    fun isSet(flag: String): Boolean = get(flag)?.isEmpty() ?: false

    fun get(flag: String): List<String>? = argparse.get(flag)

    fun unbound(vararg optionalFlag: String): List<String> = argparse.unbound(*optionalFlag)
}
