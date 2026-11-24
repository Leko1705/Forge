package com.leko.forge.intellij.parser

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.leko.forge.intellij.ForgeFileType
import com.leko.forge.intellij.ForgeLanguage
import com.leko.forge.intellij.lexer.ForgeLexerAdapter
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.psi.ForgeDeclaredName
import com.leko.forge.intellij.psi.ForgeNameReference

class ForgeFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ForgeLanguage) {
    override fun getFileType(): FileType = ForgeFileType
    override fun toString(): String = "Forge file"
}

/** A node of the parse tree; its element type says which rule ([ForgeRuleType]) or name ([ForgeNameType]). */
class ForgePsiElement(node: ASTNode) : ASTWrapperPsiElement(node)

class ForgeParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = ForgeLexerAdapter()
    override fun createParser(project: Project?): PsiParser = ForgePsiParser()
    override fun getFileNodeType(): IFileElementType = ForgeElementTypes.FILE
    override fun getWhitespaceTokens(): TokenSet = ForgeTokenTypes.WHITESPACES
    override fun getCommentTokens(): TokenSet = ForgeTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = ForgeTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement {
        val type = node.elementType as? ForgeNameType ?: return ForgePsiElement(node)
        return if (type.declaration) ForgeDeclaredName(node) else ForgeNameReference(node)
    }
    override fun createFile(viewProvider: FileViewProvider): PsiFile = ForgeFile(viewProvider)
}
