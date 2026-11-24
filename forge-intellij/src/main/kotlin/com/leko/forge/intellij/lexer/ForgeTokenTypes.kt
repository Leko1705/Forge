package com.leko.forge.intellij.lexer

import ForgeLexer
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.leko.forge.ide.lexer.ForgeEditorLexer
import com.leko.forge.intellij.ForgeLanguage

/** A token of the compiler's lexer, ForgeLexer; [antlrType] is its ANTLR token type. */
open class ForgeTokenType(val antlrType: Int, debugName: String) : IElementType(debugName, ForgeLanguage)

/**
 * A doc comment, `/** ... */`: a block comment of ForgeLexer, whose PSI is a [ForgeDocComment], so that the
 * editor can show it rendered, as KDoc.
 */
object ForgeDocCommentType : ForgeTokenType(ForgeLexer.BLOCK_COMMENT, "DOC_COMMENT"), com.intellij.psi.tree.ILeafElementType {
    override fun createLeafNode(leafText: CharSequence): com.intellij.lang.ASTNode = ForgeDocComment(this, leafText)
}

/** The PSI of a doc comment: its owner is the declaration after it. */
class ForgeDocComment(type: IElementType, text: CharSequence) :
    com.intellij.psi.impl.source.tree.PsiCommentImpl(type, text), com.intellij.psi.PsiDocCommentBase {
    override fun getOwner(): com.intellij.psi.PsiElement? {
        var next = nextSibling
        while (next is com.intellij.psi.PsiWhiteSpace) next = next.nextSibling
        return next?.let { com.intellij.psi.util.PsiTreeUtil.findChildOfType(it, com.leko.forge.intellij.psi.ForgeDeclaredName::class.java) }
    }
}

/** The IntelliJ token types: one for each token type of ForgeLexer, so the editor sees exactly the compiler's tokens. */
object ForgeTokenTypes {
    private val types: Array<ForgeTokenType?> = arrayOfNulls(ForgeLexer.VOCABULARY.maxTokenType + 1)

    init {
        for (type in 1..ForgeLexer.VOCABULARY.maxTokenType) {
            val name = ForgeLexer.VOCABULARY.getSymbolicName(type) ?: continue
            types[type] = ForgeTokenType(type, name)
        }
    }

    /** The IntelliJ type of an ANTLR token type; characters ForgeLexer has no token for are bad characters. */
    fun of(antlrType: Int): IElementType = types.getOrNull(antlrType) ?: TokenType.BAD_CHARACTER

    @JvmField val WS = of(ForgeLexer.WS)
    @JvmField val LINE_COMMENT = of(ForgeLexer.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = of(ForgeLexer.BLOCK_COMMENT)
    @JvmField val DOC_COMMENT: IElementType = ForgeDocCommentType
    @JvmField val STRING = of(ForgeLexer.STRING)
    @JvmField val REGEX_STRING = of(ForgeLexer.REGEX_STRING)
    @JvmField val IDENT = of(ForgeLexer.IDENT)

    @JvmField val WHITESPACES = TokenSet.create(WS)
    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING, REGEX_STRING)

    /** The tokens ForgeLexer skips; the parser does not see them. */
    fun isHidden(type: IElementType): Boolean = type is ForgeTokenType && ForgeEditorLexer.isHidden(type.antlrType)

    /** The keywords, which are the tokens whose literal name is a word: 'node', 'fun', '!in', 'List', ... */
    @JvmField val KEYWORDS: TokenSet = TokenSet.create(*ForgeEditorLexer.KEYWORDS.sorted().map(::of).toTypedArray())

    @JvmField val NUMBERS = TokenSet.create(
        of(ForgeLexer.INT), of(ForgeLexer.FLOAT), of(ForgeLexer.HEX), of(ForgeLexer.BIN), of(ForgeLexer.OCT),
    )

    @JvmField val OPERATORS = TokenSet.create(*listOf(
        ForgeLexer.ARROW, ForgeLexer.RARROW, ForgeLexer.EQUALS, ForgeLexer.NOTEQUALS, ForgeLexer.ELVIS,
        ForgeLexer.SPLIT, ForgeLexer.ASSIGN, ForgeLexer.NOTNULL, ForgeLexer.EXCLAMATIONMARK, ForgeLexer.QUESTIONMARK,
        ForgeLexer.STAR, ForgeLexer.AND, ForgeLexer.IAND, ForgeLexer.OR, ForgeLexer.IOR, ForgeLexer.XOR,
        ForgeLexer.CONCAT, ForgeLexer.PLUS, ForgeLexer.MINUS, ForgeLexer.SLASH, ForgeLexer.PERCENT,
        ForgeLexer.LEQ, ForgeLexer.GEQ, ForgeLexer.LT, ForgeLexer.GT,
    ).map(::of).toTypedArray())
}
