package com.leko.forge.intellij.editor

import ForgeLexer
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeRuleType

/**
 * Folds what spans several lines: the bodies of the declarations (`syn Add.type: Type = ...`,
 * `fun f(): int = ...`, `rule P.r: T -> ...`), blocks in braces (records, enums, inh equations, flows, matches,
 * ...), block comments and the imports.
 */
class ForgeFoldingBuilder : FoldingBuilderEx(), DumbAware {

    private val assign = ForgeTokenTypes.of(ForgeLexer.ASSIGN)
    private val arrow = ForgeTokenTypes.of(ForgeLexer.ARROW)
    private val lbrace = ForgeTokenTypes.of(ForgeLexer.LBRACE)
    private val rbrace = ForgeTokenTypes.of(ForgeLexer.RBRACE)

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val regions = mutableListOf<FoldingDescriptor>()
        fun multiline(range: TextRange) =
            !range.isEmpty && document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)
        fun add(node: ASTNode, range: TextRange, placeholder: String) {
            if (multiline(range)) regions += FoldingDescriptor(node, range, null, placeholder)
        }

        // the declarations are the children of the Root rule
        val declarations = root.node.getChildren(null)
            .firstOrNull { (it.elementType as? ForgeRuleType)?.ruleName == "Root" }
            ?.psi?.children.orEmpty()
        for (decl in declarations) {
            // the body: after the first `=` (or `->` of a rule) directly in the declaration
            val separator = decl.node.getChildren(null).firstOrNull {
                it.elementType == assign || (it.elementType == arrow && (decl.node.elementType as? ForgeRuleType)?.ruleName == "ParseRuleDef")
            } ?: continue
            val start = separator.textRange.endOffset
            add(decl.node, TextRange(start, decl.textRange.endOffset), " ...")
        }

        // blocks in braces
        root.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                val node = element.node
                if (node.elementType == lbrace) {
                    val close = generateSequence(node.treeNext) { it.treeNext }.firstOrNull { it.elementType == rbrace }
                    if (close != null) add(element.parent.node, TextRange(node.startOffset, close.textRange.endOffset), "{...}")
                }
                if (element is PsiComment && (node.elementType == ForgeTokenTypes.BLOCK_COMMENT || node.elementType == ForgeTokenTypes.DOC_COMMENT)) {
                    add(node, element.textRange, if (element.text.startsWith("/**")) "/** ... */" else "/* ... */")
                }
                super.visitElement(element)
            }
        })

        // the imports at the top
        val imports = declarations.filter { (it.node.elementType as? ForgeRuleType)?.ruleName == "ImportDecl" }
        if (imports.size > 1) {
            val first = imports.first()
            val range = TextRange(first.textRange.startOffset + "import ".length, imports.last().textRange.endOffset)
            add(first.node, range, "...")
        }
        return regions.distinctBy { it.range }.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."

    /** Comments of the license kind (at the top) start folded in other languages; here nothing does. */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
