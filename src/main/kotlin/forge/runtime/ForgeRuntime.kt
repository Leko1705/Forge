package forge.runtime

// ============================================================================
// Forge runtime.
//
// This file is compiled into the Forge compiler itself (the parser generator
// uses the table encoder and its tests drive the LR parser), and it is copied
// verbatim next to every generated compiler. Keep it free of dependencies.
// ============================================================================

import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/** Thrown by generated parsers on a syntax error. Lines and columns are 1-based. */
class ForgeParseException(
    val line: Int,
    val column: Int,
    val offset: Int,
    val reason: String,
) : RuntimeException("$line:$column: $reason")

/** Thrown when an attribute (transitively) depends on its own value. */
class ForgeCircularityException(val attribute: String) :
    RuntimeException("circular evaluation of attribute '$attribute'")

/** Thrown when no case of a `match` expression applies. */
class ForgeMatchException(value: Any?) : RuntimeException("no match case applies to value '$value'")

// ---------------------------------------------------------------------------
// AST support
// ---------------------------------------------------------------------------

/** Implemented by every generated AST node. */
interface ForgeNode {
    val forgeParent: ForgeNode?
    fun forgeSetParent(parent: ForgeNode?)
    /** 1-based line of the node's first token, 0 if the node was not created by a parser */
    val forgeLine: Int
    /** 1-based column of the node's first token, 0 if the node was not created by a parser */
    val forgeColumn: Int
    fun forgeSetPosition(line: Int, column: Int)
    /** The direct children of the node in declaration order. */
    fun forgeChildren(): List<ForgeNode>
}

abstract class ForgeNodeBase : ForgeNode {
    private var parentNode: ForgeNode? = null
    private var line = 0
    private var column = 0

    override val forgeLine: Int get() = line
    override val forgeColumn: Int get() = column

    override fun forgeSetPosition(line: Int, column: Int) {
        this.line = line
        this.column = column
    }

    override val forgeParent: ForgeNode? get() = parentNode

    override fun forgeSetParent(parent: ForgeNode?) {
        parentNode = parent
    }

    override fun forgeChildren(): List<ForgeNode> = emptyList()

    /** Makes this node the parent of every node contained in [value]. */
    protected fun forgeAdopt(value: Any?) {
        when (value) {
            is ForgeNode -> value.forgeSetParent(this)
            is Iterable<*> -> value.forEach { forgeAdopt(it) }
            is Map<*, *> -> value.values.forEach { forgeAdopt(it) }
        }
    }

    /** Flattens the node-valued fields [values] into a list of child nodes. */
    protected fun forgeCollectChildren(vararg values: Any?): List<ForgeNode> {
        val result = ArrayList<ForgeNode>()
        fun collect(value: Any?) {
            when (value) {
                is ForgeNode -> result.add(value)
                is Iterable<*> -> value.forEach { collect(it) }
                is Map<*, *> -> value.values.forEach { collect(it) }
            }
        }
        values.forEach { collect(it) }
        return result
    }
}

/** The root of the tree that contains this node. */
fun ForgeNode.forgeRoot(): ForgeNode {
    var node: ForgeNode = this
    while (true) node = node.forgeParent ?: return node
}

/** Visits every node of the subtree rooted at this node in pre-order. */
inline fun ForgeNode.forgeTraverse(visit: (ForgeNode) -> Unit) {
    val stack = ArrayList<ForgeNode>()
    stack.add(this)
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        visit(node)
        val children = node.forgeChildren()
        for (i in children.indices.reversed()) stack.add(children[i])
    }
}

/** Index of [child] (by identity) inside the node-valued field [container], or -1. */
fun forgeIndexOf(container: Any?, child: Any?): Int {
    if (container is List<*>) {
        for (i in container.indices) if (container[i] === child) return i
    }
    return -1
}

/** A cached attribute value. Detects circular evaluation instead of overflowing the stack. */
class ForgeLazy<T>(private val name: String, private val compute: () -> T) : ReadOnlyProperty<Any?, T> {
    private var state = 0 // 0 = not computed, 1 = computing, 2 = done
    private var value: Any? = null

    fun get(): T {
        when (state) {
            2 -> {
                @Suppress("UNCHECKED_CAST")
                return value as T
            }
            1 -> throw ForgeCircularityException(name)
        }
        state = 1
        try {
            val result = compute()
            value = result
            state = 2
            return result
        } catch (e: Throwable) {
            state = 0
            throw e
        }
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()
}

fun <T> forgeLazy(name: String, compute: () -> T): ForgeLazy<T> = ForgeLazy(name, compute)

// ---------------------------------------------------------------------------
// Builtins: implementations of commonly declared `external` functions.
// Declare them in Forge to use them, e.g. `external fun str.int(): int`.
// Functions of the Kotlin standard library (`str.trim()`, `str.substring(..)`,
// ...) need no implementation at all, declaring them as external suffices.
// ---------------------------------------------------------------------------

fun String.int(): Int = trim().toInt()
fun String.float(): Double = trim().toDouble()
fun String.bool(): Boolean = trim().toBooleanStrict()
fun String.length(): Int = length
fun String.characters(): List<String> = map { it.toString() }
fun String.unquote(): String =
    if (length >= 2 && (first() == '"' || first() == '\'') && last() == first()) substring(1, length - 1) else this
fun Int.str(): String = toString()
fun Int.float(): Double = toDouble()
fun Double.str(): String = toString()
fun Double.int(): Int = toInt()
fun Boolean.str(): String = toString()

fun forgeConcat(vararg parts: Any?): String {
    val sb = StringBuilder()
    for (part in parts) if (part != null) sb.append(part)
    return sb.toString()
}

// ---------------------------------------------------------------------------
// Parser support: semantic-value helpers used by generated reduce actions
// ---------------------------------------------------------------------------

/** Starts the value of a repetition helper nonterminal: a list of frames. */
fun forgeRepStart(frame: Array<Any?>): ArrayList<Array<Any?>> {
    val list = ArrayList<Array<Any?>>()
    list.add(frame)
    return list
}

/** Appends one iteration (frame) to the value of a repetition helper nonterminal. */
fun forgeRepAppend(list: Any?, frame: Array<Any?>): ArrayList<Array<Any?>> {
    @Suppress("UNCHECKED_CAST")
    val l = list as ArrayList<Array<Any?>>
    l.add(frame)
    return l
}

/** Field [index] of every frame of a repetition value. */
fun forgeRepField(list: Any?, index: Int): List<Any?> {
    @Suppress("UNCHECKED_CAST")
    val l = list as List<Array<Any?>>
    val result = ArrayList<Any?>(l.size)
    for (frame in l) result.add(frame[index])
    return result
}

/** Field [index] of a single frame (value of a group helper nonterminal). */
fun forgeFrameField(frame: Any?, index: Int): Any? {
    @Suppress("UNCHECKED_CAST")
    return (frame as Array<Any?>)[index]
}

// ---------------------------------------------------------------------------
// Parse tables
// ---------------------------------------------------------------------------

/**
 * Compact textual encoding of integer arrays, so that large parse tables can be
 * embedded into generated source code as string constants.
 * Every integer is written as little endian base-32 digits: continuation digits
 * followed by one final digit.
 */
object ForgeTableCodec {
    private const val FINAL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef"
    private const val CONT = "ghijklmnopqrstuvwxyz0123456789+/"

    fun encode(values: IntArray): String {
        val sb = StringBuilder()
        for (value in values) {
            require(value >= 0) { "table values must not be negative" }
            var v = value
            while (v >= 32) {
                sb.append(CONT[v and 31])
                v = v ushr 5
            }
            sb.append(FINAL[v])
        }
        return sb.toString()
    }

    fun decode(text: String): IntArray {
        val result = ArrayList<Int>(text.length)
        var value = 0
        var shift = 0
        for (c in text) {
            val f = FINAL.indexOf(c)
            if (f >= 0) {
                result.add(value or (f shl shift))
                value = 0
                shift = 0
            } else {
                val d = CONT.indexOf(c)
                require(d >= 0) { "invalid table character '$c'" }
                value = value or (d shl shift)
                shift += 5
            }
        }
        return result.toIntArray()
    }
}

/**
 * A terminal of a generated parser. Literal terminals match their [pattern]
 * verbatim, the others are regular expressions. On equally long matches a
 * literal terminal wins over a regular expression ("keywords beat identifiers").
 */
class ForgeTerminal(val name: String, val pattern: String, val literal: Boolean)

/**
 * Action encoding: `(target shl 2) or kind` with kind 1 = shift, 2 = reduce,
 * 3 = accept and 0 = error.
 *
 * Table layout (see [ForgeTableCodec]):
 * ```
 * version, terminals, nonterminals, states, productions, entries,
 * productions: (lhs, rhsLength)*,
 * entries:     startState*,
 * states:      (actionCount, (terminal, action)*, gotoCount, (nonterminal, state)*)*
 * ```
 * Terminal 0 is the end of input.
 */
class ForgeParseTables(encoded: String) {
    val terminalCount: Int
    val nonterminalCount: Int
    val stateCount: Int
    val productionLhs: IntArray
    val productionLength: IntArray
    val entryStates: IntArray
    val actions: IntArray
    val gotos: IntArray
    /** For every state the terminals that have an action, i.e. the terminals the scanner may produce. */
    val validTerminals: Array<IntArray>

    init {
        val data = ForgeTableCodec.decode(encoded)
        var i = 0
        fun next(): Int = data[i++]
        val version = next()
        require(version == 1) { "unsupported parse table version $version" }
        terminalCount = next()
        nonterminalCount = next()
        stateCount = next()
        val productionCount = next()
        val entryCount = next()
        productionLhs = IntArray(productionCount)
        productionLength = IntArray(productionCount)
        for (p in 0 until productionCount) {
            productionLhs[p] = next()
            productionLength[p] = next()
        }
        entryStates = IntArray(entryCount) { next() }
        actions = IntArray(stateCount * terminalCount)
        gotos = IntArray(stateCount * nonterminalCount) { -1 }
        validTerminals = Array(stateCount) { s ->
            val n = next()
            val valid = IntArray(n)
            for (k in 0 until n) {
                val t = next()
                actions[s * terminalCount + t] = next()
                valid[k] = t
            }
            val g = next()
            for (k in 0 until g) {
                val nt = next()
                gotos[s * nonterminalCount + nt] = next()
            }
            valid
        }
    }

    fun action(state: Int, terminal: Int): Int = actions[state * terminalCount + terminal]

    fun goto(state: Int, nonterminal: Int): Int = gotos[state * nonterminalCount + nonterminal]
}

/**
 * A scannerless LR parser driver.
 *
 * There is no separate lexing phase: whenever the parser needs a lookahead it
 * asks the scanner for the longest token among the terminals that are valid in
 * the current parse state only (context-aware scanning). Layout (whitespace,
 * comments) may appear before every token and is skipped unless a valid
 * terminal matches a strictly longer text.
 */
class ForgeLRParser(
    val tables: ForgeParseTables,
    val terminals: List<ForgeTerminal>,
    layout: List<String>,
    private val reduce: (Int, Array<Any?>) -> Any?,
) {
    constructor(
        tables: String,
        terminals: List<ForgeTerminal>,
        layout: List<String>,
        reduce: (Int, Array<Any?>) -> Any?,
    ) : this(ForgeParseTables(tables), terminals, layout, reduce)

    private val patterns: Array<Pattern?> =
        Array(terminals.size) { t -> if (t == 0 || terminals[t].literal) null else Pattern.compile(terminals[t].pattern) }
    private val layoutPatterns: List<Pattern> = layout.map { Pattern.compile(it) }

    private inner class Scanner(val source: String) {
        private val matchers: Array<Matcher?> = Array(patterns.size) { patterns[it]?.matcher(source) }
        private val layoutMatchers: List<Matcher> = layoutPatterns.map { it.matcher(source) }

        /** Length of the match of terminal [t] at [pos], or -1. Empty matches do not count. */
        fun match(t: Int, pos: Int): Int {
            val terminal = terminals[t]
            if (terminal.literal) {
                return if (terminal.pattern.isNotEmpty() && source.startsWith(terminal.pattern, pos)) terminal.pattern.length else -1
            }
            val m = matchers[t]!!
            m.region(pos, source.length)
            return if (m.lookingAt() && m.end() > pos) m.end() - pos else -1
        }

        fun layoutLength(pos: Int): Int {
            var best = 0
            for (m in layoutMatchers) {
                m.region(pos, source.length)
                if (m.lookingAt() && m.end() - pos > best) best = m.end() - pos
            }
            return best
        }

        /**
         * Scans the next token valid in [state]; returns `(terminal, start, end, alternatives...)`
         * where the alternatives are further terminals matching the same (longest) text, in
         * order of priority: literal terminals first, then in declaration order.
         */
        fun scan(state: Int, from: Int): IntArray {
            var pos = from
            while (true) {
                val candidates = ArrayList<Int>(2)
                var bestLength = 0
                for (t in tables.validTerminals[state]) {
                    if (t == 0) continue
                    val length = match(t, pos)
                    if (length <= 0 || length < bestLength) continue
                    if (length > bestLength) {
                        candidates.clear()
                        bestLength = length
                    }
                    candidates.add(t)
                }
                val layout = if (pos < source.length) layoutLength(pos) else 0
                if (layout > 0 && layout > bestLength) {
                    pos += layout
                    continue
                }
                if (candidates.isNotEmpty()) {
                    candidates.sortWith(compareBy({ !terminals[it].literal }, { it }))
                    val result = IntArray(2 + candidates.size)
                    result[0] = candidates[0]
                    result[1] = pos
                    result[2] = pos + bestLength
                    for (k in 1 until candidates.size) result[2 + k] = candidates[k]
                    return result
                }
                if (pos >= source.length) return intArrayOf(0, pos, pos)
                throw error(state, pos, "unexpected ${describeInput(pos)}")
            }
        }

        fun describeInput(pos: Int): String {
            if (pos >= source.length) return "end of input"
            val end = minOf(source.length, pos + 16).let { e ->
                val nl = source.indexOf('\n', pos)
                if (nl in pos until e) maxOf(nl, pos + 1) else e
            }
            return "'" + source.substring(pos, end) + (if (end < source.length && source[end] != '\n') "..." else "") + "'"
        }

        private val lineStarts: IntArray by lazy {
            val starts = ArrayList<Int>()
            starts.add(0)
            for (i in source.indices) if (source[i] == '\n') starts.add(i + 1)
            starts.toIntArray()
        }

        /** 1-based `(line, column)` of [offset] */
        fun position(offset: Int): IntArray {
            var index = java.util.Arrays.binarySearch(lineStarts, offset)
            if (index < 0) index = -index - 2
            return intArrayOf(index + 1, offset - lineStarts[index] + 1)
        }

        fun error(state: Int, pos: Int, reason: String): ForgeParseException {
            val (line, column) = position(pos).let { it[0] to it[1] }
            val expected = tables.validTerminals[state].map { terminals[it].name }.sorted().joinToString(", ")
            return ForgeParseException(line, column, pos, "$reason, expected one of: $expected")
        }
    }

    /**
     * Simulates reading [terminal] on a copy of the state stack (reductions included);
     * returns the resulting stack, or null if the terminal leads to a syntax error.
     */
    private fun simulate(states: IntArray, top: Int, terminal: Int): Pair<IntArray, Int>? {
        var stack = states.copyOf(maxOf(states.size, top + 2))
        var t = top
        while (true) {
            val action = tables.action(stack[t], terminal)
            when (action and 3) {
                1 -> {
                    if (++t == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[t] = action ushr 2
                    return stack to t
                }
                2 -> {
                    val production = action ushr 2
                    t -= tables.productionLength[production]
                    val target = tables.goto(stack[t], tables.productionLhs[production])
                    if (++t == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[t] = target
                }
                3 -> return stack to t
                else -> return null
            }
        }
    }

    /**
     * Lexical disambiguation: of several terminals matching the same text, prefer the
     * first one after which the next token can still be parsed. This lets e.g. a
     * keyword be used as an identifier wherever the keyword makes no sense.
     */
    private fun disambiguate(scanner: Scanner, states: IntArray, top: Int, token: IntArray): IntArray {
        for (k in 0 until token.size - 2) {
            val candidate = if (k == 0) token[0] else token[2 + k]
            val (stack, t) = simulate(states, top, candidate) ?: continue
            if (tables.action(stack[t], candidate) == 3) return chosen(token, candidate)
            val next = try {
                scanner.scan(stack[t], token[2])
            } catch (e: ForgeParseException) {
                continue
            }
            if (simulate(stack, t, next[0]) != null) return chosen(token, candidate)
        }
        return token
    }

    private fun chosen(token: IntArray, terminal: Int): IntArray = intArrayOf(terminal, token[1], token[2])

    /** Parses [source] starting with the entry rule number [entry] and returns its semantic value. */
    fun parse(source: String, entry: Int): Any? {
        val scanner = Scanner(source)
        var states = IntArray(64)
        var top = 0
        val values = ArrayList<Any?>()
        var starts = IntArray(64) // start offset of every stack entry
        states[0] = tables.entryStates[entry]
        values.add(null)
        var pos = 0
        var token: IntArray? = null
        while (true) {
            val state = states[top]
            val tok = token ?: scanner.scan(state, pos).let { if (it.size > 3) disambiguate(scanner, states, top, it) else it }.also { token = it }
            val action = tables.action(state, tok[0])
            when (action and 3) {
                1 -> {
                    if (++top == states.size) states = states.copyOf(states.size * 2)
                    states[top] = action ushr 2
                    if (top == starts.size) starts = starts.copyOf(starts.size * 2)
                    starts[top] = tok[1]
                    values.add(source.substring(tok[1], tok[2]))
                    pos = tok[2]
                    token = null
                }
                2 -> {
                    val production = action ushr 2
                    val length = tables.productionLength[production]
                    val args = arrayOfNulls<Any?>(length)
                    for (k in 0 until length) args[k] = values[values.size - length + k]
                    for (k in 0 until length) values.removeAt(values.size - 1)
                    val start = if (length > 0) starts[top - length + 1] else (token?.get(1) ?: pos)
                    top -= length
                    val value = reduce(production, args)
                    if (value is ForgeNode && value.forgeLine == 0) scanner.position(start).let { value.forgeSetPosition(it[0], it[1]) }
                    val target = tables.goto(states[top], tables.productionLhs[production])
                    if (++top == states.size) states = states.copyOf(states.size * 2)
                    states[top] = target
                    if (top == starts.size) starts = starts.copyOf(starts.size * 2)
                    starts[top] = start
                    values.add(value)
                }
                3 -> return values[values.size - 1]
                else -> throw scanner.error(state, tok[1], "unexpected ${if (tok[0] == 0) "end of input" else scanner.describeInput(tok[1])}")
            }
        }
    }
}
