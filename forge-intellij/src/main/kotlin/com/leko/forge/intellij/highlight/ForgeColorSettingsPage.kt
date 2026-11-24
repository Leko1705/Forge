package com.leko.forge.intellij.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.leko.forge.ide.parser.ForgeAntlr
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.ide.parser.nameRole
import com.leko.forge.ide.semantics.ForgeSemantics
import com.leko.forge.intellij.ForgeIcons
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import javax.swing.Icon

class ForgeColorSettingsPage : ColorSettingsPage {

    private val descriptors = arrayOf(
        AttributesDescriptor("Keyword", ForgeColors.KEYWORD),
        AttributesDescriptor("Soft keyword (op, parser options)", ForgeColors.SOFT_KEYWORD),
        AttributesDescriptor("String", ForgeColors.STRING),
        AttributesDescriptor("String expression (\${ } and \$)", ForgeColors.INTERPOLATION),
        AttributesDescriptor("Regular expression", ForgeColors.REGEX),
        AttributesDescriptor("Number", ForgeColors.NUMBER),
        AttributesDescriptor("Comments//Line comment", ForgeColors.LINE_COMMENT),
        AttributesDescriptor("Comments//Block comment", ForgeColors.BLOCK_COMMENT),
        AttributesDescriptor("Operators and punctuation//Operator", ForgeColors.OPERATOR),
        AttributesDescriptor("Operators and punctuation//Braces", ForgeColors.BRACES),
        AttributesDescriptor("Operators and punctuation//Brackets", ForgeColors.BRACKETS),
        AttributesDescriptor("Operators and punctuation//Parentheses", ForgeColors.PARENTHESES),
        AttributesDescriptor("Operators and punctuation//Dot", ForgeColors.DOT),
        AttributesDescriptor("Operators and punctuation//Comma", ForgeColors.COMMA),
        AttributesDescriptor("Operators and punctuation//Semicolon", ForgeColors.SEMICOLON),
        AttributesDescriptor("Identifier", ForgeColors.IDENTIFIER),
        AttributesDescriptor("Bad character", ForgeColors.BAD_CHARACTER),
        AttributesDescriptor("Types//Node", ForgeColors.NODE),
        AttributesDescriptor("Types//Record or enum declaration", ForgeColors.TYPE_DECLARATION),
        AttributesDescriptor("Types//Type reference", ForgeColors.TYPE_REFERENCE),
        AttributesDescriptor("Types//Builtin type", ForgeColors.BUILTIN_TYPE),
        AttributesDescriptor("Types//Type parameter", ForgeColors.TYPE_PARAMETER),
        AttributesDescriptor("Types//Trait", ForgeColors.TRAIT),
        AttributesDescriptor("Module", ForgeColors.MODULE),
        AttributesDescriptor("Enum constant", ForgeColors.ENUM_CONSTANT),
        AttributesDescriptor("Functions//Function declaration", ForgeColors.FUNCTION_DECLARATION),
        AttributesDescriptor("Functions//Function call", ForgeColors.FUNCTION_CALL),
        AttributesDescriptor("Functions//Method call", ForgeColors.METHOD_CALL),
        AttributesDescriptor("Property (val, computed)", ForgeColors.PROPERTY),
        AttributesDescriptor("Constant", ForgeColors.CONSTANT),
        AttributesDescriptor("Field or child", ForgeColors.FIELD),
        AttributesDescriptor("Parameter", ForgeColors.PARAMETER),
        AttributesDescriptor("Local variable", ForgeColors.LOCAL_VARIABLE),
        AttributesDescriptor("Attribute (syn, inh, circular, coll; computed)", ForgeColors.ATTRIBUTE),
        AttributesDescriptor("Graphs//Port", ForgeColors.PORT),
        AttributesDescriptor("Graphs//Graph", ForgeColors.GRAPH),
        AttributesDescriptor("Parsers//Parser", ForgeColors.PARSER),
        AttributesDescriptor("Parsers//Parser rule", ForgeColors.PARSER_RULE),
        AttributesDescriptor("Parsers//Annotation (@u1, @infixLeft)", ForgeColors.ANNOTATION),
    )

    override fun getIcon(): Icon = ForgeIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = ForgeSyntaxHighlighter()
    override fun getDisplayName(): String = "Forge"
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
        ForgeNameRole.entries.associate { tag(it) to ForgeColors.of(it) }

    /**
     * The demo, with the names tagged as the annotator colors them: by what the compiler resolves them to, or by
     * their role in the grammar.
     */
    override fun getDemoText(): String = tagNames(DEMO)

    private fun tag(role: ForgeNameRole) = "forge_" + role.name.lowercase()

    private fun tagNames(text: String): String {
        val (tree, _) = ForgeAntlr.parse(text)
        val semantics = ForgeSemantics.compute(text, null) { error("the demo imports nothing") }
        val names = mutableListOf<Pair<IntRange, ForgeNameRole>>()
        fun visit(node: ParseTree) {
            if (node is TerminalNode) {
                val grammarRole = (node.parent as? ParserRuleContext)?.let { nameRole(it, node.symbol) } ?: return
                val role = semantics.resolutions[node.symbol.startIndex]?.role ?: grammarRole
                if (role != ForgeNameRole.IDENTIFIER) names += node.symbol.startIndex..node.symbol.stopIndex to role
                return
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(tree)
        val result = StringBuilder()
        var position = 0
        for ((range, role) in names.sortedBy { it.first.first }) {
            result.append(text, position, range.first)
            result.append("<${tag(role)}>").append(text, range.first, range.last + 1).append("</${tag(role)}>")
            position = range.last + 1
        }
        return result.append(text, position, text.length).toString()
    }

    private companion object {
        val DEMO = """
            import "std"

            /* The tree of a small language. */
            node Program(decls: List<Decl>)
            node Decl
            node Function(name: str, body: Expr): Decl
            node Expr
            node Num(value: int): Expr
            node Add(left: Expr, right: Expr): Expr
            node Named
            for Function impl Named

            enum Type { INT, ERROR(msg: str) }
            record Diagnostic { msg: str, line: int }
            const limit: int = 4 * 3 + 1

            // attributes
            syn Expr.type: Type
            syn Num.type: Type = Type.INT
            syn Add.type: Type = if this.left.type == this.right.type then this.left.type else Type.ERROR("mismatch")
            inh depth: int = 0
            inh Add.depth { left <- this.depth + 1  right <- this.depth + 1 }
            circular(false) Expr.reachable: bool = this.reachable
            coll Program.errors: List<Diagnostic> = []
            inh program: Program? = null
            inh Program.program { decls[i] <- this }
            contrib Add -> this.program!!.errors as e = e ++ [Diagnostic("bad", 1)]

            fun Type.repr(): str = match this { Type.INT -> "int"  Type.ERROR(m) -> m  _ -> "?" }
            fun <T> List<T>.second(): T? = this[1]
            op fun Diagnostic.plus(other: Diagnostic): Diagnostic = other
            val Type.isError: bool = match this { Type.ERROR(_) -> true  _ -> false }
            ext fun str.int(): int

            // graphs
            port onTrue, onFalse
            graph cfg: Function
            flow cfg: Add(left, right) { entry(left); left -> right; right -> next with "done" }

            // parsers
            parser Lang skip '\s+' keywords 'if'
            rule Lang.expr: Expr
            rule Lang.expr: Expr @infixLeft(10) -> l: expr '\+' r: expr { Add(l, r) }
            rule Lang.expr: Num -> n: '[0-9]+' { Num(n.toInt()) }
            parser ClassFile binary
            rule ClassFile.version: int -> 0xCAFEBABE minor: @u2 major: @u2 { major }
        """.trimIndent()
    }
}
