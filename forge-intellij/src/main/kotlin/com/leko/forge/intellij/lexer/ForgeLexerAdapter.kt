package com.leko.forge.intellij.lexer

import ForgeLexer
import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import com.leko.forge.ide.lexer.ForgeEditorLexer

/**
 * The compiler's lexer, ForgeLexer, as an IntelliJ lexer: [ForgeEditorLexer], which keeps whitespace and comments
 * and makes the characters ForgeLexer has no token for bad characters.
 *
 * ForgeLexer has no modes, so every token starts in the same state and the lexer can restart at any token.
 */
class ForgeLexerAdapter : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var lexer: ForgeEditorLexer? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        this.lexer = ForgeEditorLexer(buffer, startOffset, endOffset)
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = lexer?.let { l ->
        val type = l.tokenType ?: return null
        // `/** ... */` is a doc comment (`/**/` is an empty block comment)
        if (type == ForgeLexer.BLOCK_COMMENT && l.tokenEnd - l.tokenStart > 4 && buffer.startsWith("/**", l.tokenStart)) ForgeTokenTypes.DOC_COMMENT
        else ForgeTokenTypes.of(type)
    }
    override fun getTokenStart(): Int = lexer?.tokenStart ?: 0
    override fun getTokenEnd(): Int = lexer?.tokenEnd ?: 0
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset
    override fun advance() { lexer?.advance() }
}
