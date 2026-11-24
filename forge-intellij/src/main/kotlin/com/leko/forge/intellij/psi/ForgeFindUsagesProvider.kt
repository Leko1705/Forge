package com.leko.forge.intellij.psi

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.lexer.ForgeLexerAdapter
import com.leko.forge.intellij.lexer.ForgeTokenTypes

class ForgeFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        ForgeLexerAdapter(), TokenSet.create(ForgeTokenTypes.IDENT), ForgeTokenTypes.COMMENTS, ForgeTokenTypes.STRINGS,
    )

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is ForgeDeclaredName
    override fun getHelpId(element: PsiElement): String? = null
    override fun getDescriptiveName(element: PsiElement): String = (element as? ForgeNameElement)?.name ?: element.text
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)

    override fun getType(element: PsiElement): String = when ((element as? ForgeNameElement)?.role) {
        ForgeNameRole.NODE_DECLARATION -> "node"
        ForgeNameRole.TYPE_DECLARATION -> "type"
        ForgeNameRole.TYPE_PARAMETER -> "type parameter"
        ForgeNameRole.ENUM_CONSTANT -> "enum constant"
        ForgeNameRole.FUNCTION_DECLARATION -> "function"
        ForgeNameRole.PROPERTY -> "property"
        ForgeNameRole.CONSTANT -> "constant"
        ForgeNameRole.FIELD -> "field"
        ForgeNameRole.PARAMETER -> "parameter"
        ForgeNameRole.LOCAL_VARIABLE -> "variable"
        ForgeNameRole.ATTRIBUTE -> "attribute"
        ForgeNameRole.PORT -> "port"
        ForgeNameRole.GRAPH -> "graph"
        ForgeNameRole.PARSER -> "parser"
        ForgeNameRole.PARSER_RULE -> "rule"
        ForgeNameRole.MODULE -> "module"
        ForgeNameRole.TRAIT -> "trait"
        else -> "name"
    }
}
