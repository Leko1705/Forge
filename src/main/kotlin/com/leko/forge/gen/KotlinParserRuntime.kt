package com.leko.forge.gen

/**
 * The parsing engine of generated parsers, put into generated files that declare a `parser`.
 *
 * A packrat parser (results are memoized per rule, position and precedence) with support for left
 * recursion (seed growing), abstract rules with operators (precedence climbing), text and binary input.
 * Rule bodies run imperatively: a mismatch throws a preallocated exception and the combinators backtrack
 * by resetting the position. Errors report the furthest position reached and what was expected there.
 */
internal val KOTLIN_PARSER_RUNTIME: String = """
// ==== Forge parser runtime ===================================================================

/** A failed parse: where it failed and what would have been accepted there. */
class ParseException(
    message: kotlin.String,
    /** Character offset for text input, bit offset for binary input. */
    val offset: kotlin.Int,
    /** 1-based line (0 for binary input). */
    val line: kotlin.Int,
    /** 1-based column (0 for binary input). */
    val column: kotlin.Int,
    /** Literals as `'fun'`, tokens by name, rules and regular expressions as `/regex/`. */
    val expected: kotlin.collections.List<kotlin.String>,
    val source: kotlin.String?,
) : kotlin.RuntimeException(message)

open class __Rule<T>(val name: kotlin.String, private val body: (__ParseScope.() -> T)?) {
    open fun run(scope: __ParseScope, precedence: kotlin.Int): T = body!!(scope)
    override fun toString(): kotlin.String = name
}

/** A rule made of alternatives and operators (with precedence and associativity), added with [on], [prefix], ... */
class __AbstractRule<T>(name: kotlin.String) : __Rule<T>(name, null) {
    class Alternative<T>(
        val precedence: kotlin.Int,
        /** Binding strength of the operand; `null` for plain alternatives. */
        val operandPrecedence: kotlin.Int?,
        val parse: (__ParseScope.() -> T)? = null,
        val extend: (__ParseScope.(T) -> T)? = null,
    )

    /** Plain alternatives and prefix operators: they start an expression. */
    val alternatives: kotlin.collections.MutableList<Alternative<T>> = kotlin.collections.ArrayList()

    /** Infix and postfix operators: they extend an expression. */
    val operators: kotlin.collections.MutableList<Alternative<T>> = kotlin.collections.ArrayList()

    fun on(alternative: __ParseScope.() -> T) { alternatives.add(Alternative(kotlin.Int.MAX_VALUE, null, parse = alternative)) }
    fun prefix(precedence: kotlin.Int, operator: __ParseScope.() -> T) { alternatives.add(Alternative(precedence, precedence, parse = operator)) }
    fun infixLeft(precedence: kotlin.Int, operator: __ParseScope.(T) -> T) { operators.add(Alternative(precedence, precedence + 1, extend = operator)) }
    fun infixRight(precedence: kotlin.Int, operator: __ParseScope.(T) -> T) { operators.add(Alternative(precedence, precedence, extend = operator)) }
    fun postfix(precedence: kotlin.Int, operator: __ParseScope.(T) -> T) { operators.add(Alternative(precedence, precedence, extend = operator)) }

    override fun run(scope: __ParseScope, precedence: kotlin.Int): T = scope.__climb(this, precedence)
}

/** A terminal: a regular expression. Literals must not end inside a word (`fun` does not match `funny`). */
class __Terminal(regex: kotlin.String, val literal: kotlin.String?, name: kotlin.String?) {
    val pattern: java.util.regex.Pattern = java.util.regex.Pattern.compile(regex)
    val display: kotlin.String = name ?: if (literal != null) "'" + literal + "'" else "/" + regex + "/"
}

/** An exact bit pattern. */
class __Bits(bits: kotlin.String, val display: kotlin.String) {
    val bits: kotlin.BooleanArray = kotlin.BooleanArray(bits.length) { bits[it] == '1' }
}

/** A label of a rule: assigned while parsing, read by the producer. */
class __Label<T>(private val name: kotlin.String) {
    private var set = false
    private var value: T? = null

    fun set(value: T) {
        this.value = value
        set = true
    }

    @Suppress("UNCHECKED_CAST")
    operator fun getValue(thisRef: kotlin.Any?, property: kotlin.reflect.KProperty<*>): T {
        kotlin.check(set) { "label '" + name + "' was not matched" }
        return value as T
    }
}

class __ParseScope(
    /** The text, or the bytes read as ISO-8859-1 characters for binary input. */
    private val text: kotlin.String,
    private val bytes: kotlin.ByteArray?,
    private val skip: java.util.regex.Pattern?,
    private val keywords: kotlin.collections.Set<kotlin.String>,
    private val source: kotlin.String?,
) {
    /** Thrown on a mismatch; preallocated without a stack trace, so backtracking stays cheap. */
    private object Mismatch : kotlin.RuntimeException("mismatch", null, false, false)

    private class Frame
    private class Key(val rule: __Rule<*>, val pos: kotlin.Int, val precedence: kotlin.Int) {
        override fun hashCode(): kotlin.Int = (java.lang.System.identityHashCode(rule) * 31 + pos) * 31 + precedence
        override fun equals(other: kotlin.Any?): kotlin.Boolean =
            other is Key && other.rule === rule && other.pos == pos && other.precedence == precedence
    }
    /** [head] is set while the rule runs at this position, to detect left recursion. */
    private class Memo(var ok: kotlin.Boolean, var value: kotlin.Any?, var end: kotlin.Int, var head: Frame?)

    /** Position units per character: 1 for text, 8 bits for binary input. */
    private val unit = if (bytes == null) 1 else 8
    private val size = text.length * unit

    /** Position in characters (text) or bits (binary). */
    private var pos = 0
    private val memo = java.util.HashMap<Key, Memo>()
    private val stack = kotlin.collections.ArrayList<Frame>()
    private val leftRecursive = java.util.HashSet<Frame>()
    private val involved = java.util.HashSet<Frame>()

    private var furthest = -1
    private val expected = kotlin.collections.LinkedHashSet<kotlin.String>()

    // ---- text -----------------------------------------------------------------------------

    fun __terminal(terminal: __Terminal): kotlin.String {
        skipIgnored()
        if (pos % unit != 0) __fail(terminal.display)
        val start = pos / unit
        val m = terminal.pattern.matcher(text)
        m.region(start, text.length)
        m.useTransparentBounds(true)
        m.useAnchoringBounds(false)
        if (!m.lookingAt()) __fail(terminal.display)
        val value = m.group()
        val end = m.end()
        val literal = terminal.literal
        if (literal != null) {
            // a literal ending in a letter or digit must not be followed by one (`fun` is not `funny`)
            if (literal.isNotEmpty() && isWordChar(literal.last()) && end < text.length && isWordChar(text[end])) __fail(terminal.display)
        } else if (value in keywords) {
            __fail(terminal.display)
        }
        pos = end * unit
        return value
    }

    private fun skipIgnored() {
        if (skip == null || pos % unit != 0) return
        while (true) {
            val m = skip.matcher(text)
            m.region(pos / unit, text.length)
            if (!m.lookingAt() || m.end() == pos / unit) return
            pos = m.end() * unit
        }
    }

    // ---- binary ---------------------------------------------------------------------------

    fun __bits(pattern: __Bits): kotlin.String {
        requireBinary()
        val width = pattern.bits.size
        if (pos + width > size) __fail(pattern.display)
        for (i in 0 until width) if (bitAt(pos + i) != pattern.bits[i]) __fail(pattern.display)
        pos += width
        return pattern.display
    }

    /** [width] bits (0..32) as an unsigned number, most significant bit first. */
    fun __uint(width: kotlin.Int): kotlin.Int {
        requireBinary()
        kotlin.require(width in 0..32) { "width must be between 0 and 32" }
        if (pos + width > size) __fail(width.toString() + " more bits")
        var value = 0L
        for (i in 0 until width) value = (value shl 1) or (if (bitAt(pos + i)) 1L else 0L)
        pos += width
        return value.toInt()
    }

    /** [width] bits (1..32) as a two's complement number. */
    fun __sint(width: kotlin.Int): kotlin.Int {
        kotlin.require(width in 1..32) { "width must be between 1 and 32" }
        val value = __uint(width).toLong() and 0xFFFFFFFFL
        return (if (width < 32 && (value ushr (width - 1)) and 1L == 1L) value - (1L shl width) else value).toInt()
    }

    fun __bit(): kotlin.Boolean = __uint(1) == 1
    fun __u1(): kotlin.Int = __uint(8)
    fun __u2(): kotlin.Int = __uint(16)
    fun __u4(): kotlin.Int = __uint(32)

    /** [count] bytes, each as 0..255. */
    fun __bytes(count: kotlin.Int): kotlin.collections.List<kotlin.Int> {
        requireBinary()
        if (pos + count * 8 > size) __fail(count.toString() + " more bytes")
        return kotlin.collections.List(count) { __uint(8) }
    }

    private fun bitAt(bitPos: kotlin.Int): kotlin.Boolean =
        (bytes!![bitPos ushr 3].toInt() shr (7 - (bitPos and 7))) and 1 == 1

    private fun requireBinary() {
        kotlin.check(bytes != null) { "bit and byte primitives need binary input" }
    }

    // ---- rules and failure ----------------------------------------------------------------

    /** Fails at the current position, reporting [expected] in the error message. */
    fun __fail(expected: kotlin.String): kotlin.Nothing {
        if (pos > furthest) {
            furthest = pos
            this.expected.clear()
        }
        if (pos == furthest) this.expected.add(expected)
        throw Mismatch
    }

    // ---- combinators ----------------------------------------------------------------------

    fun <T> __optional(body: () -> T): T? {
        val start = pos
        return try {
            body()
        } catch (_: Mismatch) {
            pos = start
            null
        }
    }

    fun <T> __many(body: () -> T): kotlin.collections.List<T> {
        val result = kotlin.collections.ArrayList<T>()
        while (true) {
            val start = pos
            try {
                result.add(body())
            } catch (_: Mismatch) {
                pos = start
                return result
            }
            if (pos == start) return result // matched nothing: stop, or this would loop forever
        }
    }

    fun <T> __some(body: () -> T): kotlin.collections.List<T> {
        val first = body()
        return kotlin.collections.listOf(first) + __many(body)
    }

    fun <T> __repeat(count: kotlin.Int, body: () -> T): kotlin.collections.List<T> = kotlin.collections.List(count) { body() }

    /** `&x`: [body] must match here; consumes nothing. */
    fun __and(body: () -> kotlin.Any?): kotlin.Unit {
        val start = pos
        try {
            body()
        } finally {
            pos = start
        }
    }

    /** `!x`: [body] must not match here; consumes nothing. */
    fun __not(description: kotlin.String, body: () -> kotlin.Any?): kotlin.Unit {
        val start = pos
        val matched = try {
            body()
            true
        } catch (_: Mismatch) {
            false
        } finally {
            pos = start
        }
        if (matched) __fail("not " + description)
    }

    /** Ordered choice: the first alternative that matches. */
    fun <T> __oneOf(vararg alternatives: () -> T): T {
        val start = pos
        for (alternative in alternatives) {
            try {
                return alternative()
            } catch (_: Mismatch) {
                pos = start
            }
        }
        throw Mismatch
    }

    // ---- abstract rules -------------------------------------------------------------------

    private class OperatorContext(val rule: __AbstractRule<*>, val operandPrecedence: kotlin.Int)

    /** The operator whose body is running, or `null` inside a rule (a barrier). */
    private val operators = kotlin.collections.ArrayList<OperatorContext?>()

    /** Inside an operator: its operand, parsed with the binding strength of its precedence and associativity. */
    @Suppress("UNCHECKED_CAST")
    fun <T> __operand(): T {
        val context = operators.lastOrNull() ?: kotlin.error("an operand outside of an operator")
        return __apply(context.rule as __Rule<T>, context.operandPrecedence)
    }

    private inline fun <R> inOperator(rule: __AbstractRule<*>, operandPrecedence: kotlin.Int?, block: () -> R): R {
        operators.add(operandPrecedence?.let { OperatorContext(rule, it) })
        try {
            return block()
        } finally {
            operators.removeAt(operators.size - 1)
        }
    }

    /** Precedence climbing: one alternative or prefix operator, then infix and postfix operators binding strongly enough. */
    fun <T> __climb(rule: __AbstractRule<T>, minPrecedence: kotlin.Int): T {
        val start = pos
        var left: T = run {
            for (alternative in rule.alternatives) {
                try {
                    return@run inOperator(rule, alternative.operandPrecedence) { alternative.parse!!(this) }.also { record(it, start, pos) }
                } catch (_: Mismatch) {
                    pos = start
                }
            }
            kotlin.check(rule.alternatives.isNotEmpty()) { "abstract rule '" + rule.name + "' has no alternatives" }
            throw Mismatch
        }
        extend@ while (true) {
            for (operator in rule.operators) {
                if (operator.precedence < minPrecedence) continue
                val before = pos
                try {
                    val extended = inOperator(rule, operator.operandPrecedence) { operator.extend!!(this, left) }
                    if (pos == before) continue // consumed nothing: would loop forever
                    left = extended
                    record(extended, start, pos)
                    continue@extend
                } catch (_: Mismatch) {
                    pos = before
                }
            }
            return left
        }
    }

    // ---- engine ---------------------------------------------------------------------------

    /**
     * Applies [rule] with memoization and left recursion support (seed growing, see Warth et al.,
     * "Packrat Parsers Can Support Left Recursion").
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> __apply(rule: __Rule<T>, precedence: kotlin.Int = kotlin.Int.MIN_VALUE): T {
        val start = pos
        val key = Key(rule, start, precedence)
        memo[key]?.let { m ->
            m.head?.let { head -> // the rule calls itself at the same position: left recursion
                leftRecursive.add(head)
                for (i in stack.lastIndexOf(head) + 1 until stack.size) involved.add(stack[i])
            }
            if (!m.ok) throw Mismatch
            pos = m.end
            return m.value as T
        }

        val frame = Frame()
        val m = Memo(false, null, start, frame) // a recursive call fails at first (the seed)
        memo[key] = m
        stack.add(frame)
        operators.add(null)
        try {
            attempt(rule, precedence, start, m)
            if (frame in leftRecursive) {
                // grow the seed as long as each attempt consumes more input
                while (m.ok) {
                    val value = m.value
                    val end = m.end
                    if (!attempt(rule, precedence, start, m) || m.end <= end) {
                        m.ok = true; m.value = value; m.end = end
                        break
                    }
                }
            }
        } finally {
            operators.removeAt(operators.size - 1)
            stack.removeAt(stack.size - 1)
            m.head = null
            if (frame in involved) memo.remove(key)
            leftRecursive.remove(frame)
            involved.remove(frame)
        }
        if (!m.ok) {
            pos = start
            throw Mismatch
        }
        pos = m.end
        record(m.value, start, m.end)
        return m.value as T
    }

    private fun attempt(rule: __Rule<*>, precedence: kotlin.Int, start: kotlin.Int, into: Memo): kotlin.Boolean {
        pos = start
        try {
            into.value = rule.run(this, precedence)
            into.end = pos
            into.ok = true
        } catch (_: Mismatch) {
            into.ok = false
        }
        return into.ok
    }

    /** Remembers where a node was parsed from; the innermost rule that returned it wins. */
    private fun record(value: kotlin.Any?, start: kotlin.Int, end: kotlin.Int) {
        if (value !is __Node || value.__span != null) return
        val from = skippedFrom(start) / unit
        val to = (end + unit - 1) / unit
        value.__span = if (bytes == null) {
            Span(source, lineOf(from), columnOf(from), from, to - from, text.substring(from, kotlin.math.max(from, to)))
        } else {
            Span(source, 0, 0, from, to - from, "")
        }
    }

    /** Where a node really starts: after the ignored text at [from]. */
    private fun skippedFrom(from: kotlin.Int): kotlin.Int {
        if (skip == null || from % unit != 0) return from
        var p = from
        while (true) {
            val m = skip.matcher(text)
            m.region(p / unit, text.length)
            if (!m.lookingAt() || m.end() == p / unit) return p
            p = m.end() * unit
        }
    }

    private fun lineOf(offset: kotlin.Int): kotlin.Int {
        var line = 1
        for (i in 0 until kotlin.math.min(offset, text.length)) if (text[i] == '\n') line++
        return line
    }

    private fun columnOf(offset: kotlin.Int): kotlin.Int = offset - (text.lastIndexOf('\n', offset - 1) + 1) + 1

    fun <T> __parseAll(rule: __Rule<T>): T {
        try {
            val result = __apply(rule)
            skipIgnored()
            if (pos == size) return result
            if (pos > furthest) {
                furthest = pos
                expected.clear()
            }
            if (pos == furthest) expected.add("end of input")
        } catch (_: Mismatch) {
        }
        throw error()
    }

    private fun error(): ParseException {
        val at = kotlin.math.max(furthest, 0)
        val expected = this.expected.toList()
        val described = expected.joinToString(" or ")
        val prefix = if (source != null) source + ":" else ""
        if (bytes == null) {
            val found = if (at < text.length) "'" + text[at] + "'" else "end of input"
            val line = lineOf(at)
            val column = columnOf(at)
            return ParseException(prefix + line + ":" + column + ": expected " + described + ", found " + found, at, line, column, expected, source)
        }
        val byte = at / 8
        val bit = at % 8
        val where = if (bit == 0) "byte " + byte else "byte " + byte + ", bit " + bit
        val found = if (byte < bytes.size) "0x" + (bytes[byte].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') else "end of input"
        return ParseException(prefix + where + ": expected " + described + ", found " + found, at, 0, 0, expected, source)
    }

    private fun isWordChar(c: kotlin.Char): kotlin.Boolean = c.isLetterOrDigit() || c == '_'
}

/** Parses all of [text] with [rule]. */
fun <T> __parseText(rule: __Rule<T>, text: kotlin.String, skip: kotlin.String?, keywords: kotlin.collections.Set<kotlin.String>, source: kotlin.String?): T =
    __ParseScope(text, null, skip?.let { java.util.regex.Pattern.compile(it) }, keywords, source).__parseAll(rule)

/** Parses all bits of [bytes] with [rule]. */
fun <T> __parseBinary(rule: __Rule<T>, bytes: kotlin.ByteArray, skip: kotlin.String?, keywords: kotlin.collections.Set<kotlin.String>, source: kotlin.String?): T {
    val text = kotlin.CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
    return __ParseScope(text, bytes, skip?.let { java.util.regex.Pattern.compile(it) }, keywords, source).__parseAll(rule)
}

// ==== end of Forge parser runtime ============================================================
"""
