package com.leko.forge.ide.parser

import ForgeParser
import com.leko.forge.ide.lexer.ForgeEditorLexer
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenFactory
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.IntStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenFactory
import org.antlr.v4.runtime.TokenSource

/** The source of the tokens of [ForgeAntlr.tokens]: they are made by [ForgeEditorLexer], not read from it. */
private class TextTokenSource(private val input: CharStream) : TokenSource {
    private var factory: TokenFactory<*> = CommonTokenFactory.DEFAULT
    override fun nextToken(): Token = throw UnsupportedOperationException("the tokens are made in advance")
    override fun getLine(): Int = 0
    override fun getCharPositionInLine(): Int = 0
    override fun getInputStream(): CharStream = input
    // no source name: the spans of the analyzed file have none
    override fun getSourceName(): String = IntStream.UNKNOWN_SOURCE_NAME
    override fun setTokenFactory(factory: TokenFactory<*>) { this.factory = factory }
    override fun getTokenFactory(): TokenFactory<*> = factory
}

/** A syntax error of ForgeParser at [offset], the start of the token it is reported at. */
data class ForgeSyntaxError(val offset: Int, val message: String)

/** The compiler's lexer and parser on the text of an editor. */
object ForgeAntlr {

    /**
     * The tokens of [text] as ForgeLexer reads them for ForgeParser (without whitespace and comments), from
     * [ForgeEditorLexer], the lexer of the editors, so the offsets of the parse tree are those of the editor's tokens.
     */
    fun tokens(text: CharSequence): List<Token> {
        val lexer = ForgeEditorLexer(text)
        val tokens = mutableListOf<Token>()
        var line = 1
        var lineStart = 0
        var position = 0
        fun moveTo(offset: Int) {
            while (position < offset) {
                if (text[position] == '\n') { line++; lineStart = position + 1 }
                position++
            }
        }
        // ANTLR's error recovery makes up missing tokens from the source of the tokens
        val input = ANTLRInputStream(text.toString())
        val source = org.antlr.v4.runtime.misc.Pair<TokenSource, CharStream>(TextTokenSource(input), input)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != ForgeEditorLexer.BAD_CHARACTER && !ForgeEditorLexer.isHidden(type)) {
                moveTo(lexer.tokenStart)
                tokens += CommonToken(source, type, Token.DEFAULT_CHANNEL, lexer.tokenStart, lexer.tokenEnd - 1).apply {
                    this.text = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                    startIndex = lexer.tokenStart
                    stopIndex = lexer.tokenEnd - 1
                    this.line = line
                    charPositionInLine = lexer.tokenStart - lineStart
                }
            }
            lexer.advance()
        }
        moveTo(text.length)
        tokens += CommonToken(source, Token.EOF, Token.DEFAULT_CHANNEL, text.length, text.length - 1).apply {
            this.text = "<EOF>"
            startIndex = text.length
            stopIndex = text.length - 1
            this.line = line
            charPositionInLine = text.length - lineStart
        }
        return tokens
    }

    /** The parse tree of ForgeParser for [text], with the syntax errors it reports. */
    fun parse(text: CharSequence): Pair<ForgeParser.RootContext, List<ForgeSyntaxError>> {
        val errors = mutableListOf<ForgeSyntaxError>()
        val parser = ForgeParser(CommonTokenStream(ListTokenSource(tokens(text))))
        parser.removeErrorListeners()
        parser.addErrorListener(object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int,
                msg: String, e: RecognitionException?,
            ) {
                val offset = (offendingSymbol as? Token)?.startIndex?.takeIf { it >= 0 } ?: text.length
                errors += ForgeSyntaxError(offset, msg)
            }
        })
        return parser.root() to errors
    }
}
