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
 */
class ForgeEditorLexer(private val buffer: CharSequence, private val startOffset: Int = 0, private val endOffset: Int = buffer.length) {

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

        /** Whether ForgeLexer skips tokens of [type] (whitespace and comments); the parser does not see them. */
        fun isHidden(type: Int): Boolean = type == ForgeLexer.WS || type == ForgeLexer.LINE_COMMENT || type == ForgeLexer.BLOCK_COMMENT

        /** The keywords: the token types whose literal name is a word, e.g. 'node', 'fun', '!in', 'List'. */
        val KEYWORDS: Set<Int> = (1..ForgeLexer.VOCABULARY.maxTokenType).filter { type ->
            val literal = ForgeLexer.VOCABULARY.getLiteralName(type) ?: return@filter false
            literal.trim('\'').trimStart('!').let { it.isNotEmpty() && it.all(Char::isLetter) }
        }.toSet()
    }
}
