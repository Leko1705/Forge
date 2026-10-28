package com.leko.forge.lr

import com.leko.forge.model.SourceSpan

/**
 * A plain BNF grammar, the input of the LR table construction.
 *
 * Symbols are encoded as integers: a terminal `t` is `t` itself (`t >= 0`),
 * a nonterminal `n` is `-(n + 1)`. Terminal 0 is always the end of input.
 */
class LRGrammar {

    val terminals: MutableList<TerminalSpec> = mutableListOf(TerminalSpec("end of input", "", true))

    val nonterminals: MutableList<String> = mutableListOf()

    val productions: MutableList<Production> = mutableListOf()

    /** Nonterminals the generated parser can start with; every entry gets its own start state. */
    val entries: MutableList<Int> = mutableListOf()

    private val terminalIndex = mutableMapOf<Pair<Boolean, String>, Int>()

    fun terminal(pattern: String, literal: Boolean): Int =
        terminalIndex.getOrPut(literal to pattern) {
            val name = if (literal) "'$pattern'" else "/$pattern/"
            terminals += TerminalSpec(name, pattern, literal)
            terminals.size - 1
        }

    fun nonterminal(name: String): Int {
        nonterminals += name
        return nonterminals.size - 1
    }

    fun production(
        lhs: Int,
        rhs: List<Int>,
        precedence: Precedence? = null,
        span: SourceSpan? = null,
    ): Int {
        productions += Production(productions.size, lhs, rhs.toIntArray(), precedence, span)
        return productions.size - 1
    }

    fun symbolName(sym: Int): String =
        if (sym >= 0) terminals[sym].name else nonterminals[-sym - 1]

    fun describe(p: Production, dot: Int? = null): String = buildString {
        append(nonterminals[p.lhs]).append(" →")
        for (i in p.rhs.indices) {
            if (i == dot) append(" •")
            append(' ').append(symbolName(p.rhs[i]))
        }
        if (dot == p.rhs.size) append(" •")
        if (p.rhs.isEmpty() && dot == null) append(" ε")
    }

    companion object {
        fun nt(index: Int): Int = -(index + 1)
    }
}

data class TerminalSpec(val name: String, val pattern: String, val literal: Boolean)

enum class Assoc { LEFT, RIGHT, NONASSOC, NONE }

/** Precedence of a production; higher levels bind tighter. */
data class Precedence(val level: Int, val assoc: Assoc)

class Production(
    val index: Int,
    val lhs: Int,
    val rhs: IntArray,
    val precedence: Precedence?,
    val span: SourceSpan?,
)
