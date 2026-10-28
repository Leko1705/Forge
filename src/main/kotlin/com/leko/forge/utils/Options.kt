package com.leko.forge.utils

import com.leko.forge.context.Context
import com.leko.forge.input.ArgumentParser

/** The command line options of a compilation (javac's `Options` analog). */
class Options private constructor() {
    companion object {
        fun instance(context: Context): Options =
            context.getServices(Options::class) { Options() }
    }

    var argparse: ArgumentParser = ArgumentParser(emptyArray())
        private set

    fun set(args: Array<String>) {
        argparse = ArgumentParser(args)
    }

    fun isSet(flag: String): Boolean = get(flag) != null

    fun get(flag: String): List<String>? = argparse.get(flag)

    fun unbound(vararg optionalFlag: String): List<String> = argparse.unbound(*optionalFlag)
}
