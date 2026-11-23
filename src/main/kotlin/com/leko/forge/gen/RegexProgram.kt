package com.leko.forge.gen

/**
 * Compiles a Java regular expression to a program for the small backtracking matcher that the C++, Rust and
 * OCaml runtimes contain (they have no regular expressions of their own with Java's syntax). The program
 * matches bytes of UTF-8 text; characters outside of ASCII in a class stand for all bytes >= 0x80.
 *
 * Every instruction is three ints `op a b`:
 * - CHAR c, ANY (not \n or \r), SET set negated (a byte in ranges [sets][set]), MATCH
 * - SPLIT x y (try x, then y), JMP x
 * - LOOK negated: runs the program at pc + 2 (up to its MATCH) at the position; continues at pc + 1 if it
 *   matched (or did not, if negated); pc + 1 is a JMP over that program
 * - BOL, EOL (end of input, or before a final \n), WORD (boundary), NOTWORD
 * - MARK k (slot k = position), PROGRESS k (fail unless the position moved): loops whose body can match
 *   nothing stop
 */
internal class RegexProgram(val code: IntArray, val sets: List<IntArray>, val slots: Int) {
    companion object {
        const val CHAR = 0
        const val ANY = 1
        const val SET = 2
        const val SPLIT = 3
        const val JMP = 4
        const val MATCH = 5
        const val LOOK = 6
        const val BOL = 7
        const val EOL = 8
        const val WORD = 9
        const val NOTWORD = 10
        const val MARK = 11
        const val PROGRESS = 12

        fun compile(regex: String): RegexProgram = RegexCompiler(regex).compile()
    }
}

class UnsupportedRegexException(regex: String, why: String) : RuntimeException("regular expression /$regex/: $why")

private sealed interface Re {
    data class Bytes(val bytes: List<Int>) : Re
    data class Set(val ranges: List<IntRange>, val negated: Boolean) : Re
    data object Any : Re
    data class Seq(val items: List<Re>) : Re
    data class Alt(val items: List<Re>) : Re
    data class Repeat(val body: Re, val min: Int, val max: Int, val greedy: Boolean) : Re
    data class Look(val body: Re, val negated: Boolean) : Re
    data object Bol : Re
    data object Eol : Re
    data class Word(val negated: Boolean) : Re
}

private class RegexCompiler(private val regex: String) {
    private var i = 0

    private fun fail(why: String): Nothing = throw UnsupportedRegexException(regex, why)

    fun compile(): RegexProgram {
        val ast = alternation()
        if (i < regex.length) fail("unexpected '${regex[i]}' at $i")
        val e = Emitter()
        e.emit(ast)
        e.op(RegexProgram.MATCH, 0, 0)
        return RegexProgram(e.code.toIntArray(), e.sets, e.slots)
    }

    // ---- parsing -------------------------------------------------------------------------------

    private fun peek(): Char? = regex.getOrNull(i)

    private fun alternation(): Re {
        val items = mutableListOf(sequence())
        while (peek() == '|') {
            i++
            items += sequence()
        }
        return items.singleOrNull() ?: Re.Alt(items)
    }

    private fun sequence(): Re {
        val items = mutableListOf<Re>()
        while (true) {
            val c = peek() ?: break
            if (c == '|' || c == ')') break
            items += quantified(atom() ?: continue)
        }
        return items.singleOrNull() ?: Re.Seq(items)
    }

    private fun quantified(atom: Re): Re {
        var result = atom
        while (true) {
            val (min, max) = when (peek()) {
                '*' -> { i++; 0 to -1 }
                '+' -> { i++; 1 to -1 }
                '?' -> { i++; 0 to 1 }
                '{' -> {
                    val close = regex.indexOf('}', i)
                    val spec = if (close < 0) null else regex.substring(i + 1, close)
                    val m = spec?.let { Regex("(\\d+)(,(\\d*))?").matchEntire(it) } ?: return result
                    i = close + 1
                    val lo = m.groupValues[1].toInt()
                    val hi = if (m.groupValues[2].isEmpty()) lo else m.groupValues[3].ifEmpty { "-1" }.toInt()
                    lo to hi
                }
                else -> return result
            }
            var greedy = true
            when (peek()) {
                '?' -> { i++; greedy = false }
                '+' -> i++ // possessive: matched greedily
            }
            result = Re.Repeat(result, min, max, greedy)
        }
    }

    /** An atom, or null for constructs that match nothing (`\Q\E`). */
    private fun atom(): Re? {
        val c = regex[i++]
        return when (c) {
            '(' -> {
                var look: Boolean? = null
                if (peek() == '?') {
                    i++
                    when (regex.getOrNull(i++)) {
                        ':' -> {}
                        '=' -> look = false
                        '!' -> look = true
                        '<' -> {
                            if (peek() == '=' || peek() == '!') fail("lookbehind is not supported")
                            val close = regex.indexOf('>', i)
                            if (close < 0) fail("unterminated group name")
                            i = close + 1
                        }
                        else -> fail("group flags are not supported")
                    }
                }
                val body = alternation()
                if (peek() != ')') fail("missing ')'")
                i++
                if (look != null) Re.Look(body, look) else body
            }
            '[' -> charClass()
            '.' -> Re.Any
            '^' -> Re.Bol
            '$' -> Re.Eol
            '\\' -> escape(inClass = false)
            else -> literal(c)
        }
    }

    private fun literal(c: Char): Re {
        if (Character.isHighSurrogate(c) && i < regex.length) {
            val cp = Character.toCodePoint(c, regex[i++])
            return Re.Bytes(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF })
        }
        return Re.Bytes(c.toString().toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF })
    }

    private val digits = listOf('0'.code..'9'.code)
    private val word = listOf('a'.code..'z'.code, 'A'.code..'Z'.code, '0'.code..'9'.code, '_'.code..'_'.code)
    private val space = listOf(' '.code..' '.code, 9..13)

    /** An escape outside (a [Re]) or inside a class (a [Re.Set] or [Re.Bytes] of one character). */
    private fun escape(inClass: Boolean): Re {
        val c = regex.getOrNull(i++) ?: fail("trailing backslash")
        return when (c) {
            'd' -> Re.Set(digits, false)
            'D' -> Re.Set(digits, true)
            'w' -> Re.Set(word, false)
            'W' -> Re.Set(word, true)
            's' -> Re.Set(space, false)
            'S' -> Re.Set(space, true)
            't' -> Re.Bytes(listOf(9))
            'n' -> Re.Bytes(listOf(10))
            'r' -> Re.Bytes(listOf(13))
            'f' -> Re.Bytes(listOf(12))
            'a' -> Re.Bytes(listOf(7))
            'e' -> Re.Bytes(listOf(27))
            '0' -> {
                var v = 0
                var n = 0
                while (n < 3 && peek()?.let { it in '0'..'7' } == true) { v = v * 8 + (regex[i++] - '0'); n++ }
                literal(v.toChar())
            }
            'x' -> {
                if (peek() == '{') fail("\\x{...} is not supported")
                val hex = regex.substring(i, minOf(i + 2, regex.length))
                i += 2
                literal(hex.toInt(16).toChar())
            }
            'u' -> {
                val hex = regex.substring(i, minOf(i + 4, regex.length))
                i += 4
                literal(hex.toInt(16).toChar())
            }
            'b' -> if (inClass) fail("\\b in a class") else Re.Word(false)
            'B' -> if (inClass) fail("\\B in a class") else Re.Word(true)
            'A' -> if (inClass) fail("\\A in a class") else Re.Bol
            'z', 'Z' -> if (inClass) fail("\\z in a class") else Re.Eol
            'Q' -> {
                val end = regex.indexOf("\\E", i).let { if (it < 0) regex.length else it }
                val quoted = regex.substring(i, end)
                i = minOf(end + 2, regex.length)
                if (quoted.isEmpty()) Re.Seq(emptyList())
                else Re.Bytes(quoted.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF })
            }
            'p', 'P', 'k', 'G', 'R', 'X', 'h', 'H', 'v', 'V' -> fail("\\$c is not supported")
            else -> if (c.isLetterOrDigit()) fail("\\$c is not supported") else literal(c)
        }
    }

    private fun charClass(): Re {
        var negated = false
        if (peek() == '^') { i++; negated = true }
        val ranges = mutableListOf<IntRange>()
        var first = true
        fun single(): Int? {
            val c = regex.getOrNull(i) ?: fail("missing ']'")
            if (c == '\\') {
                i++
                val e = escape(inClass = true)
                if (e is Re.Set) {
                    ranges += if (e.negated) complement(e.ranges) else e.ranges
                    return null
                }
                val bytes = (e as Re.Bytes).bytes
                return if (bytes.size == 1) bytes[0] else -1
            }
            if (c == '[') fail("nested classes are not supported")
            i++
            if (Character.isHighSurrogate(c)) { i++; return -1 }
            return if (c.code < 128) c.code else -1
        }
        while (true) {
            val c = peek() ?: fail("missing ']'")
            if (c == ']' && !first) { i++; break }
            if (c == '&' && regex.getOrNull(i + 1) == '&') fail("class intersection is not supported")
            first = false
            val lo = single() ?: continue
            if (peek() == '-' && regex.getOrNull(i + 1) != ']' && regex.getOrNull(i + 1) != null) {
                i++
                val hi = single() ?: fail("a range ends in a class")
                ranges += if (lo < 0 || hi < 0) listOf(0x80..0xFF) else listOf(lo..hi)
            } else {
                ranges += if (lo < 0) 0x80..0xFF else lo..lo
            }
        }
        return Re.Set(ranges, negated)
    }

    private fun complement(ranges: List<IntRange>): List<IntRange> {
        val inSet = BooleanArray(256)
        for (r in ranges) for (b in r) if (b in 0..255) inSet[b] = true
        val result = mutableListOf<IntRange>()
        var b = 0
        while (b < 256) {
            if (inSet[b]) { b++; continue }
            val start = b
            while (b < 256 && !inSet[b]) b++
            result += start until b
        }
        return result
    }

    // ---- code ----------------------------------------------------------------------------------

    private class Emitter {
        val code = mutableListOf<Int>()
        val sets = mutableListOf<IntArray>()
        var slots = 0

        val pc: Int get() = code.size / 3

        fun op(op: Int, a: Int, b: Int): Int {
            val at = pc
            code += op; code += a; code += b
            return at
        }

        fun patch(at: Int, a: Int, b: Int) {
            code[at * 3 + 1] = a
            code[at * 3 + 2] = b
        }

        fun emit(re: Re) {
            when (re) {
                is Re.Bytes -> re.bytes.forEach { op(RegexProgram.CHAR, it, 0) }
                is Re.Set -> {
                    sets += re.ranges.flatMap { listOf(it.first, it.last) }.toIntArray()
                    op(RegexProgram.SET, sets.size - 1, if (re.negated) 1 else 0)
                }
                Re.Any -> op(RegexProgram.ANY, 0, 0)
                Re.Bol -> op(RegexProgram.BOL, 0, 0)
                Re.Eol -> op(RegexProgram.EOL, 0, 0)
                is Re.Word -> op(if (re.negated) RegexProgram.NOTWORD else RegexProgram.WORD, 0, 0)
                is Re.Seq -> re.items.forEach { emit(it) }
                is Re.Alt -> {
                    val jumps = mutableListOf<Int>()
                    re.items.forEachIndexed { index, item ->
                        if (index < re.items.size - 1) {
                            val split = op(RegexProgram.SPLIT, 0, 0)
                            emit(item)
                            jumps += op(RegexProgram.JMP, 0, 0)
                            patch(split, split + 1, pc)
                        } else {
                            emit(item)
                        }
                    }
                    for (j in jumps) patch(j, pc, 0)
                }
                is Re.Look -> {
                    op(RegexProgram.LOOK, if (re.negated) 1 else 0, 0)
                    val skip = op(RegexProgram.JMP, 0, 0)
                    emit(re.body)
                    op(RegexProgram.MATCH, 0, 0)
                    patch(skip, pc, 0)
                }
                is Re.Repeat -> {
                    repeat(re.min) { emit(re.body) }
                    if (re.max < 0) loop(re.body, re.greedy)
                    else {
                        val exits = mutableListOf<Int>()
                        repeat(re.max - re.min) {
                            exits += op(RegexProgram.SPLIT, 0, 0)
                            emit(re.body)
                        }
                        for (s in exits) if (re.greedy) patch(s, s + 1, pc) else patch(s, pc, s + 1)
                    }
                }
            }
        }

        /** `body*`; a body that can match nothing must make progress, or the loop stops. */
        private fun loop(body: Re, greedy: Boolean) {
            val start = op(RegexProgram.SPLIT, 0, 0)
            val nullable = canBeEmpty(body)
            val slot = if (nullable) slots++ else -1
            if (nullable) op(RegexProgram.MARK, slot, 0)
            emit(body)
            if (nullable) op(RegexProgram.PROGRESS, slot, 0)
            op(RegexProgram.JMP, start, 0)
            if (greedy) patch(start, start + 1, pc) else patch(start, pc, start + 1)
        }

        private fun canBeEmpty(re: Re): Boolean = when (re) {
            is Re.Bytes -> re.bytes.isEmpty()
            is Re.Set, Re.Any -> false
            Re.Bol, Re.Eol, is Re.Word, is Re.Look -> true
            is Re.Seq -> re.items.all { canBeEmpty(it) }
            is Re.Alt -> re.items.any { canBeEmpty(it) }
            is Re.Repeat -> re.min == 0 || canBeEmpty(re.body)
        }
    }
}
