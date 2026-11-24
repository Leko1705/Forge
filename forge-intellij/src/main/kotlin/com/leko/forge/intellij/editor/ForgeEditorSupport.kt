package com.leko.forge.intellij.editor

import ForgeLexer
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.leko.forge.intellij.lexer.ForgeTokenTypes

class ForgeBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(ForgeTokenTypes.of(ForgeLexer.LBRACE), ForgeTokenTypes.of(ForgeLexer.RBRACE), true),
        BracePair(ForgeTokenTypes.of(ForgeLexer.LPAREN), ForgeTokenTypes.of(ForgeLexer.RPAREN), false),
        BracePair(ForgeTokenTypes.of(ForgeLexer.LBRACKET), ForgeTokenTypes.of(ForgeLexer.RBRACKET), false),
    )

    override fun getPairs(): Array<BracePair> = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}

class ForgeCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
    override fun getLineCommentTokenType(): IElementType = ForgeTokenTypes.LINE_COMMENT
    override fun getBlockCommentTokenType(): IElementType = ForgeTokenTypes.BLOCK_COMMENT
    override fun getDocumentationCommentTokenType(): IElementType? = null
    override fun getDocumentationCommentPrefix(): String = "/**"
    override fun getDocumentationCommentLinePrefix(): String = "*"
    override fun getDocumentationCommentSuffix(): String = "*/"
    override fun isDocumentationComment(element: PsiComment?): Boolean = element?.text?.startsWith("/**") == true
}

class ForgeQuoteHandler : SimpleTokenSetQuoteHandler(ForgeTokenTypes.STRINGS)
