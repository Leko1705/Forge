package com.leko.forge.intellij.parser

import ForgeParser
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.leko.forge.ide.parser.ForgeAntlr
import com.leko.forge.ide.parser.ForgeSyntaxError
import com.leko.forge.ide.parser.isDeclaration
import com.leko.forge.ide.parser.nameRole
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * Parses with the compiler's parser, ForgeParser, and builds the PSI tree from its parse tree: every rule context
 * becomes a node of its own type, names become nodes with their [ForgeNameRole], and the syntax errors ForgeParser
 * reports become error elements. So the editor reports exactly the syntax errors the compiler reports.
 */
class ForgePsiParser : PsiParser {

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val (tree, errors) = ForgeAntlr.parse(builder.originalText)
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
                    val importPath = context is ForgeParser.ImportDeclContext && token === context.path
                    val mark = if (role != null || importPath) builder.mark() else null
                    reportErrors(builder.currentOffset)
                    while (!builder.eof() && builder.currentOffset <= token.stopIndex) consume()
                    when {
                        importPath -> mark?.done(ForgeElementTypes.IMPORT_PATH)
                        role != null -> mark?.done(ForgeElementTypes.of(role, isDeclaration(context, token)))
                    }
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
}
