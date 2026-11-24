package com.leko.forge.ide.lexer

import ForgeLexer
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.Token

/**
 * The compiler's lexer, ForgeLexer, as an editor needs it: every character of the text is in a token. Whitespace
 * and comments, which ForgeLexer skips, are kept as tokens; the characters ForgeLexer has no token for are
 * [BAD_CHARACTER]s. A block comment that is never closed is a comment up to the end, as it is while it is typed.
 *
 * It lexes [buffer] from [startOffset] to [endOffset]; [tokenType] (an ANTLR token type, `null` at the end),
 * [tokenStart] and [tokenEnd] are offsets in [buffer]. ForgeLexer has no modes, so every token starts in the same
 * state and the lexer can restart at any token.
 *
 * With [interpolations], a string with expressions (`"a ${x + 1} $y"`) is split: its text ([ForgeLexer.STRING]),
 * the [INTERPOLATION_START] (`${` or `$`) and [INTERPOLATION_END] (`}`) of each expression, and the tokens of the
 * expressions. Editors color them as code; within a string, [state] is not 0, so the lexer restarts at its start.
 */
class ForgeEditorLexer(
    private val buffer: CharSequence,
    private val startOffset: Int = 0,
    private val endOffset: Int = buffer.length,
    private val interpolations: Boolean = false,
) {
    /** The parts of a split string after the current one: type, start, end. */
    private val parts = ArrayDeque<Triple<Int, Int, Int>>()

    /** 0 where the lexer can restart; not 0 in a string with expressions after its first part. */
    var state: Int = 0
        private set

    /** ForgeLexer, keeping the tokens of `-> skip` rules (whitespace and comments) instead of dropping them. */
    private class KeepingLexer(input: CharStream) : ForgeLexer(input) {
        init { removeErrorListeners() }
        override fun skip() {}
    }

    // ANTLRInputStream indexes UTF-16 chars, as editors do (CharStreams would index code points)
    private var lexer: KeepingLexer? = KeepingLexer(ANTLRInputStream(buffer.subSequence(startOffset, endOffset).toString()))

    /** The next ANTLR token, read but not yet reached because of bad characters before it. */
    private var pending: Token? = null

    /** The ANTLR token type of the current token, [BAD_CHARACTER], or `null` at the end. */
    var tokenType: Int? = null
        private set
    var tokenStart = startOffset
        private set
    var tokenEnd = startOffset
        private set

    init { advance() }

    fun advance() {
        if (parts.isNotEmpty()) {
            val (type, start, end) = parts.removeFirst()
            tokenType = type
            tokenStart = start
            tokenEnd = end
            state = 1
            return
        }
        state = 0
        advanceToken()
        if (interpolations && tokenType == ForgeLexer.STRING) {
            val split = splitString(tokenStart, tokenEnd)
            if (split.size > 1) {
                parts += split.drop(1)
                tokenType = split[0].first
                tokenEnd = split[0].third
            }
        }
    }

    /** The parts of the string from [start] to [end]: text, the starts and ends of expressions, their tokens. */
    private fun splitString(start: Int, end: Int): List<Triple<Int, Int, Int>> {
        val result = mutableListOf<Triple<Int, Int, Int>>()
        var text = start
        val last = if (end - start >= 2 && buffer[end - 1] == '"') end - 1 else end
        var i = start + 1
        fun flushText(upTo: Int) {
            if (upTo > text) result += Triple(ForgeLexer.STRING, text, upTo)
        }
        while (i < last) {
            val c = buffer[i]
            when {
                c == '\\' -> i += 2
                c == '$' && i + 1 < last && buffer[i + 1] == '{' -> {
                    flushText(i)
                    val close = closingBrace(i + 2, last)
                    result += Triple(INTERPOLATION_START, i, i + 2)
                    val inner = ForgeEditorLexer(buffer, i + 2, close, interpolations = true)
                    while (true) {
                        val type = inner.tokenType ?: break
                        result += Triple(type, inner.tokenStart, inner.tokenEnd)
                        inner.advance()
                    }
                    i = if (close < last) {
                        result += Triple(INTERPOLATION_END, close, close + 1)
                        close + 1
                    } else close
                    text = i
                }
                c == '$' && i + 1 < last && (buffer[i + 1].isLetter() || buffer[i + 1] == '_') -> {
                    flushText(i)
                    var stop = i + 1
                    while (stop < last && (buffer[stop].isLetterOrDigit() || buffer[stop] == '_')) stop++
                    result += Triple(INTERPOLATION_START, i, i + 1)
                    result += Triple(ForgeLexer.IDENT, i + 1, stop)
                    i = stop
                    text = i
                }
                else -> i++
            }
        }
        flushText(end)
        return result
    }

    /** The `}` that closes an expression in a string from [from]; braces nest, strings in it are skipped. */
    private fun closingBrace(from: Int, limit: Int): Int {
        var depth = 0
        var i = from
        while (i < limit) {
            when (buffer[i]) {
                '{' -> depth++
                '}' -> if (depth == 0) return i else depth--
                '"' -> {
                    i++
                    while (i < limit && buffer[i] != '"') {
                        if (buffer[i] == '\\') i++
                        else if (buffer[i] == '$' && i + 1 < limit && buffer[i + 1] == '{') i = closingBrace(i + 2, limit)
                        i++
                    }
                }
            }
            i++
        }
        return limit
    }

    private fun advanceToken() {
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            tokenType = null
            return
        }
        val lexer = lexer ?: run { tokenType = null; return }
        val token = pending ?: lexer.nextToken()
        pending = null
        val start = if (token.type == Token.EOF) endOffset else startOffset + token.startIndex
        when {
            // characters ForgeLexer dropped with an error
            start > tokenStart -> {
                pending = token
                tokenType = BAD_CHARACTER
                tokenEnd = start
            }
            token.type == ForgeLexer.SLASH && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '*' -> {
                // a block comment start lexed as SLASH STAR: the comment is never closed
                tokenType = ForgeLexer.BLOCK_COMMENT
                tokenEnd = endOffset
                this.lexer = null
            }
            else -> {
                tokenType = token.type
                tokenEnd = startOffset + token.stopIndex + 1
            }
        }
    }

    companion object {
        /** The type of characters ForgeLexer has no token for; no ANTLR token has it. */
        const val BAD_CHARACTER = Token.INVALID_TYPE

        /** `${` or `$` before an expression in a string (with [ForgeEditorLexer.interpolations]); no ANTLR token has it. */
        const val INTERPOLATION_START = -10

        /** `}` after an expression in a string. */
        const val INTERPOLATION_END = -11

        /** Whether ForgeLexer skips tokens of [type] (whitespace and comments); the parser does not see them. */
        fun isHidden(type: Int): Boolean = type == ForgeLexer.WS || type == ForgeLexer.LINE_COMMENT || type == ForgeLexer.BLOCK_COMMENT

        /** The keywords: the token types whose literal name is a word, e.g. 'node', 'fun', '!in', 'List'. */
        val KEYWORDS: Set<Int> = (1..ForgeLexer.VOCABULARY.maxTokenType).filter { type ->
            val literal = ForgeLexer.VOCABULARY.getLiteralName(type) ?: return@filter false
            literal.trim('\'').trimStart('!').let { it.isNotEmpty() && it.all(Char::isLetter) }
        }.toSet()
    }
}
