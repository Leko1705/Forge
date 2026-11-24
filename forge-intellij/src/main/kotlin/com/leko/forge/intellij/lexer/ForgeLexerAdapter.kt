package com.leko.forge.intellij.lexer

import ForgeLexer
import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.Token

/**
 * The compiler's lexer, ForgeLexer, as an IntelliJ lexer. Whitespace and comments, which ForgeLexer skips, are
 * kept as tokens; the characters ForgeLexer has no token for are bad characters. A block comment that is never closed is a
 * comment up to the end, as it is while it is typed.
 *
 * ForgeLexer has no modes, so every token starts in the same state and the lexer can restart at any token.
 */
class ForgeLexerAdapter : LexerBase() {

    /** ForgeLexer, keeping the tokens of `-> skip` rules (whitespace and comments) instead of dropping them. */
    private class KeepingLexer(input: CharStream) : ForgeLexer(input) {
        init { removeErrorListeners() }
        override fun skip() {}
    }

    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var lexer: KeepingLexer? = null

    /** The next ANTLR token, read but not yet reached because of bad characters before it. */
    private var pending: Token? = null

    private var tokenType: IElementType? = null
    private var tokenStart = 0
    private var tokenEnd = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        // ANTLRInputStream indexes UTF-16 chars, as the editor does (CharStreams would index code points)
        this.lexer = KeepingLexer(ANTLRInputStream(buffer.subSequence(startOffset, endOffset).toString()))
        this.pending = null
        this.tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
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
                tokenType = TokenType.BAD_CHARACTER
                tokenEnd = start
            }
            token.type == ForgeLexer.SLASH && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '*' -> {
                // a block comment start lexed as SLASH STAR: the comment is never closed
                tokenType = ForgeTokenTypes.BLOCK_COMMENT
                tokenEnd = endOffset
                this.lexer = null
            }
            else -> {
                tokenType = ForgeTokenTypes.of(token.type)
                tokenEnd = startOffset + token.stopIndex + 1
            }
        }
    }
}
