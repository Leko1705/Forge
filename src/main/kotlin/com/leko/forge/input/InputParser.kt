package com.leko.forge.input

/**
 * Command line parsing: flags with a fixed number of arguments ([arity]); `-f`
 * takes all following arguments up to the next flag. Everything else is positional.
 */
class ArgumentParser(input: Array<String>, arity: Map<String, Int> = DEFAULT_ARITY) {

    companion object {
        val DEFAULT_ARITY = mapOf(
            "-d" to 1, "-o" to 1, "-p" to 1, "-t" to 1,
            "-f" to -1, "--f" to -1,
        )
    }

    private val flags = linkedMapOf<String, MutableList<String>>()
    private val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < input.size) {
            val arg = input[i++]
            if (!arg.startsWith("-") || arg == "-") {
                positional += arg
                continue
            }
            val values = flags.getOrPut(arg) { mutableListOf() }
            when (val n = arity[arg] ?: 0) {
                -1 -> while (i < input.size && !input[i].startsWith("-")) values += input[i++]
                else -> repeat(n) { if (i < input.size) values += input[i++] }
            }
        }
    }

    /** the arguments of [flag], or null if it is absent */
    fun get(flag: String): List<String>? = flags[flag]

    /** the positional arguments, preceded by the arguments of the first present [optionalFlag] */
    fun unbound(vararg optionalFlag: String): List<String> =
        optionalFlag.flatMap { flags[it] ?: emptyList() } + positional

}
