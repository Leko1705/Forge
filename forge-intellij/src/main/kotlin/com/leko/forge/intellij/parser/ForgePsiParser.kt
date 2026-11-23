package com.leko.forge.intellij.parser

import ForgeParser
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.leko.forge.intellij.lexer.ForgeLexerAdapter
import com.leko.forge.intellij.lexer.ForgeTokenType
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode

/** A syntax error of ForgeParser at [offset], the start of the token it is reported at. */
data class ForgeSyntaxError(val offset: Int, val message: String)

/**
 * Parses with the compiler's parser, ForgeParser, and builds the PSI tree from its parse tree: every rule context
 * becomes a node of its own type, names become nodes with their [ForgeNameRole], and the syntax errors ForgeParser
 * reports become error elements. So the editor reports exactly the syntax errors the compiler reports.
 */
class ForgePsiParser : PsiParser {

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val (tree, errors) = parseAntlr(builder.originalText)
        val rootMarker = builder.mark()
        TreeBuilder(builder, errors).run {
            visit(tree)
            advanceTo(Int.MAX_VALUE)
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }

    private class TreeBuilder(private val builder: PsiBuilder, errors: List<ForgeSyntaxError>) {
        private val errors = ArrayDeque(errors.sortedBy { it.offset })

        /** Moves past everything before [offset]; reports the errors and bad characters on the way. */
        fun advanceTo(offset: Int) {
            while (true) {
                reportErrors(builder.currentOffset)
                if (builder.eof() || builder.currentOffset >= offset) break
                consume()
            }
            if (builder.eof()) reportErrors(Int.MAX_VALUE)
        }

        private fun reportErrors(upTo: Int) {
            while (errors.isNotEmpty() && errors.first().offset <= upTo) builder.error(errors.removeFirst().message)
        }

        private fun consume() {
            if (builder.tokenType == TokenType.BAD_CHARACTER) {
                val mark = builder.mark()
                builder.advanceLexer()
                mark.error("unexpected character")
            } else {
                builder.advanceLexer()
            }
        }

        fun visit(tree: ParseTree) {
            when (tree) {
                is TerminalNode -> {
                    val token = tree.symbol
                    // EOF, and tokens ANTLR's error recovery made up
                    if (token.type == Token.EOF || token.startIndex < 0 || token.tokenIndex < 0) return
                    advanceTo(token.startIndex)
                    val context = tree.parent as? ParserRuleContext
                    val role = context?.let { nameRole(it, token) }
                    val mark = role?.let { builder.mark() }
                    reportErrors(builder.currentOffset)
                    while (!builder.eof() && builder.currentOffset <= token.stopIndex) consume()
                    mark?.done(ForgeElementTypes.of(role))
                }
                is ParserRuleContext -> {
                    val start = tree.start
                    val stop = tree.stop
                    // a rule that matched nothing
                    if (start == null || stop == null || start.type == Token.EOF || stop.tokenIndex < start.tokenIndex) {
                        tree.children?.forEach(::visit)
                        return
                    }
                    advanceTo(start.startIndex)
                    val mark = builder.mark()
                    tree.children?.forEach(::visit)
                    mark.done(ForgeElementTypes.of(tree))
                }
            }
        }
    }

    companion object {
        /**
         * The tokens of [text] as ForgeLexer reads them for ForgeParser (without whitespace and comments), from
         * the same lexer the editor uses, so the offsets of the parse tree are those of the PSI tokens.
         */
        fun antlrTokens(text: CharSequence): List<Token> {
            val lexer = ForgeLexerAdapter()
            lexer.start(text, 0, text.length, 0)
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
            while (true) {
                val type = lexer.tokenType ?: break
                if (type is ForgeTokenType && !ForgeTokenTypes.isHidden(type)) {
                    moveTo(lexer.tokenStart)
                    tokens += CommonToken(type.antlrType, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()).apply {
                        startIndex = lexer.tokenStart
                        stopIndex = lexer.tokenEnd - 1
                        this.line = line
                        charPositionInLine = lexer.tokenStart - lineStart
                    }
                }
                lexer.advance()
            }
            moveTo(text.length)
            tokens += CommonToken(Token.EOF, "<EOF>").apply {
                startIndex = text.length
                stopIndex = text.length - 1
                this.line = line
                charPositionInLine = text.length - lineStart
            }
            return tokens
        }

        /** The parse tree of ForgeParser for [text], with the syntax errors it reports. */
        fun parseAntlr(text: CharSequence): Pair<ForgeParser.RootContext, List<ForgeSyntaxError>> {
            val errors = mutableListOf<ForgeSyntaxError>()
            val parser = ForgeParser(CommonTokenStream(ListTokenSource(antlrTokens(text))))
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
}
