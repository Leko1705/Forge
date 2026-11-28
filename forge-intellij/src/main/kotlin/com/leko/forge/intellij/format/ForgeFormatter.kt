package com.leko.forge.intellij.format

import ForgeLexer
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.FormatterUtil
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.leko.forge.intellij.ForgeLanguage
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeRuleType

/**
 * Formats Forge as the examples are written: declarations start at the start of a line, their further lines are
 * indented once, and what is inside brackets (`{ }`, `( )`, `[ ]`) that span lines once more than the line that
 * opens them; the closing bracket is where that line starts. Spaces: after commas, around `=`, `->`, `<-` and the
 * comparison and logic operators. The rest (`expr*`, `l:expr`, `T?`, alignments in a line) stays as it is.
 */
class ForgeFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val settings = formattingContext.codeStyleSettings
        val file = formattingContext.containingFile
        return FormattingModelProvider.createFormattingModelForPsiFile(
            file, FileBlock(file.node, spacing(settings)), settings,
        )
    }

    private fun spacing(settings: CodeStyleSettings): SpacingBuilder {
        fun t(type: Int) = ForgeTokenTypes.of(type)
        val around = TokenSet.create(
            t(ForgeLexer.ASSIGN), t(ForgeLexer.ARROW), t(ForgeLexer.RARROW), t(ForgeLexer.EQUALS), t(ForgeLexer.NOTEQUALS),
            t(ForgeLexer.AND), t(ForgeLexer.OR), t(ForgeLexer.ELVIS), t(ForgeLexer.CONCAT),
        )
        return SpacingBuilder(settings, ForgeLanguage)
            .before(t(ForgeLexer.COMMA)).spaces(0)
            .after(t(ForgeLexer.COMMA)).spaces(1)
            .around(around).spaces(1)
            .around(t(ForgeLexer.DOT)).spaces(0)
            .around(t(ForgeLexer.SAFE_DOT)).spaces(0)
    }
}

private val BRACKETS: Map<IElementType, IElementType> = mapOf(
    ForgeTokenTypes.of(ForgeLexer.LBRACE) to ForgeTokenTypes.of(ForgeLexer.RBRACE),
    ForgeTokenTypes.of(ForgeLexer.LPAREN) to ForgeTokenTypes.of(ForgeLexer.RPAREN),
    ForgeTokenTypes.of(ForgeLexer.LBRACKET) to ForgeTokenTypes.of(ForgeLexer.RBRACKET),
)

private fun ASTNode.isBlank() = elementType == TokenType.WHITE_SPACE || elementType == ForgeTokenTypes.WS || textLength == 0

/**
 * The blocks of [nodes]: their tokens, at any depth, except that a bracket with its closing bracket (both children
 * of the same node) becomes one [BracketBlock].
 */
private fun flatten(nodes: List<ASTNode>, spacing: SpacingBuilder, indentOf: (Block) -> Indent): List<Block> {
    val blocks = mutableListOf<Block>()
    fun visit(children: List<ASTNode>) {
        var i = 0
        while (i < children.size) {
            val child = children[i]
            val close = BRACKETS[child.elementType]
            val end = if (close != null) (i + 1 until children.size).firstOrNull { children[it].elementType == close } else null
            when {
                child.isBlank() -> {}
                end != null -> {
                    blocks += BracketBlock(children.subList(i, end + 1), spacing)
                    i = end
                }
                child.firstChildNode == null -> blocks += LeafBlock(child, spacing)
                else -> visit(child.getChildren(null).toList())
            }
            i++
        }
    }
    visit(nodes)
    return blocks.onEach { (it as ForgeBlock).indentValue = indentOf(it) }
}

private sealed interface ForgeBlock : Block {
    var indentValue: Indent
}

/** The file: its declarations and comments, at the start of lines. */
private class FileBlock(node: ASTNode, private val spacing: SpacingBuilder) : AbstractBlock(node, null, null) {
    override fun buildChildren(): List<Block> {
        val blocks = mutableListOf<Block>()
        fun visit(parent: ASTNode) {
            for (child in parent.getChildren(null)) {
                when {
                    child.isBlank() -> {}
                    (child.elementType as? ForgeRuleType)?.ruleName == "Root" -> visit(child)
                    child.firstChildNode == null -> blocks += LeafBlock(child, spacing).apply { indentValue = Indent.getNoneIndent() }
                    (child.elementType as? ForgeRuleType)?.ruleName == "ModDef" -> blocks += ModuleBlock(child, spacing, Indent.getNoneIndent())
                    else -> blocks += DeclarationBlock(child, spacing)
                }
            }
        }
        visit(myNode)
        return blocks
    }

    override fun getIndent(): Indent = Indent.getNoneIndent()
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun isLeaf(): Boolean = false
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
}

/** `mod name { ... }`: its declarations are indented once, and each is formatted as a top level declaration. */
private class ModuleBlock(node: ASTNode, private val spacing: SpacingBuilder, private val indent: Indent) : AbstractBlock(node, null, null) {
    override fun buildChildren(): List<Block> = buildList {
        for (child in myNode.getChildren(null)) {
            when {
                child.isBlank() -> {}
                // the declarations: a module in it, or any other
                (child.elementType as? ForgeRuleType)?.ruleName == "ModDef" -> add(ModuleBlock(child, spacing, Indent.getNormalIndent()))
                child.firstChildNode != null -> add(DeclarationBlock(child, spacing, Indent.getNormalIndent()))
                // `mod`, its name, the braces and comments
                else -> add(LeafBlock(child, spacing).apply {
                    indentValue = if (child.elementType in ForgeTokenTypes.COMMENTS) Indent.getNormalIndent() else Indent.getNoneIndent()
                })
            }
        }
    }

    override fun getIndent(): Indent = indent
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = spacing.getSpacing(this, child1, child2)
    override fun isLeaf(): Boolean = false
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNormalIndent(), null)
}

/** A top level declaration (or one of a module, [indent]ed): its lines after the first are indented once. */
private class DeclarationBlock(node: ASTNode, private val spacing: SpacingBuilder, private val indent: Indent = Indent.getNoneIndent()) : AbstractBlock(node, null, null) {
    override fun buildChildren(): List<Block> {
        var first = true
        return flatten(listOf(myNode).flatMap { it.getChildren(null).toList() }, spacing) {
            if (first) { first = false; Indent.getNoneIndent() } else Indent.getNormalIndent()
        }
    }

    override fun getIndent(): Indent = indent
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = spacing.getSpacing(this, child1, child2)
    override fun isLeaf(): Boolean = false
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNormalIndent(), null)

    /** A declaration that goes on: Enter after its `=`, `->`, an operator, ... indents the next line. */
    override fun isIncomplete(): Boolean {
        if (FormatterUtil.isIncomplete(myNode)) return true
        var last = myNode.lastChildNode
        while (last != null && (last.isBlank() || last.elementType in ForgeTokenTypes.COMMENTS)) last = last.treePrev
        while (last?.lastChildNode != null) last = last.lastChildNode
        return last is PsiErrorElement || last?.elementType in CONTINUING
    }
}

/** Tokens after which a declaration goes on. */
private val CONTINUING: TokenSet = TokenSet.create(*listOf(
    ForgeLexer.ASSIGN, ForgeLexer.ARROW, ForgeLexer.RARROW, ForgeLexer.COLON, ForgeLexer.COMMA, ForgeLexer.IOR,
    ForgeLexer.DOT, ForgeLexer.SAFE_DOT, ForgeLexer.PLUS, ForgeLexer.MINUS, ForgeLexer.STAR, ForgeLexer.SLASH, ForgeLexer.PERCENT,
    ForgeLexer.AND, ForgeLexer.OR, ForgeLexer.EQUALS, ForgeLexer.NOTEQUALS, ForgeLexer.ELVIS, ForgeLexer.CONCAT,
    ForgeLexer.LT, ForgeLexer.GT, ForgeLexer.LEQ, ForgeLexer.GEQ, ForgeLexer.THEN, ForgeLexer.ELSE, ForgeLexer.IF,
).map(ForgeTokenTypes::of).toTypedArray())

/** `{ ... }`, `( ... )` or `[ ... ]`: what is inside is indented once more than the line of the opening bracket. */
private class BracketBlock(private val nodes: List<ASTNode>, private val spacing: SpacingBuilder) :
    AbstractBlock(nodes.first(), null, null), ForgeBlock {
    override var indentValue: Indent = Indent.getNoneIndent()

    override fun getTextRange(): TextRange = TextRange(nodes.first().startOffset, nodes.last().textRange.endOffset)

    override fun buildChildren(): List<Block> {
        // the brackets themselves, and between them what is inside (flattening all of nodes would find this block again)
        val open = LeafBlock(nodes.first(), spacing).apply { indentValue = Indent.getNoneIndent() }
        val close = LeafBlock(nodes.last(), spacing).apply { indentValue = Indent.getNoneIndent() }
        return listOf(open) + flatten(nodes.subList(1, nodes.size - 1), spacing) { Indent.getNormalIndent() } + close
    }

    override fun getIndent(): Indent = indentValue
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = spacing.getSpacing(this, child1, child2)
    override fun isLeaf(): Boolean = false
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNormalIndent(), null)
}

/** A token. */
private class LeafBlock(node: ASTNode, private val spacing: SpacingBuilder) : AbstractBlock(node, null, null), ForgeBlock {
    override var indentValue: Indent = Indent.getNoneIndent()
    override fun buildChildren(): List<Block> = emptyList()
    override fun getIndent(): Indent = indentValue
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun isLeaf(): Boolean = true
}
