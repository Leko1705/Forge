package com.leko.forge.input


class ArgumentParser(private val input: Array<String>) {

    fun get(flag: String): List<String>? {
        val idx = input.indexOfFirst { it == flag }
        if (idx == -1) return null
        val remaining = input.toList().subList(idx + 1, input.size)
        return buildList {
            for (e in remaining) {
                if (e.startsWith("-")) {
                    break
                }
                add(e)
            }
        }
    }

    fun unbound(vararg optionalFlag: String): List<String> = buildList {
        if (optionalFlag.isNotEmpty()) {
            for (flag in optionalFlag) {
                val args = get(flag)
                if (args != null) return args
            }
        }
        for (e in input.reversed()) {
            if (e.startsWith("-")) {
                break
            }
            add(e)
        }

    }

}
