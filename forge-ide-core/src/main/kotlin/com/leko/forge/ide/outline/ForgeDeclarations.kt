package com.leko.forge.ide.outline

import ForgeLexer
import ForgeParser
import com.leko.forge.ide.lexer.ForgeEditorLexer
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.ide.parser.isDeclaration
import com.leko.forge.ide.parser.nameRole
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode

/** A name that a declaration declares: [token], directly below [context], with its [role]. */
class ForgeDeclaredName(val token: Token, val context: ParserRuleContext, val role: ForgeNameRole) {
    val name: String get() = token.text
    val start: Int get() = token.startIndex
    val end: Int get() = token.stopIndex + 1
}

/**
 * A declaration of the outline of a file: [name] declares it; [label] is how it is shown, [start] and [end] are the
 * offsets of the whole declaration. [children] are the fields of records and nodes and the constants of enums.
 */
data class ForgeOutlineEntry(
    val name: ForgeDeclaredName,
    val label: String,
    val start: Int,
    val end: Int,
    val children: List<ForgeOutlineEntry>,
    /** Whether it is an enum (not a record). */
    val isEnum: Boolean = false,
    /** Whether it is an interface node (one without parameters). */
    val isInterface: Boolean = false,
)

/** What the documentation of a declaration shows: its [signature], what [kind] of declaration it is, its doc comment. */
data class ForgeDeclarationInfo(val signature: String, val kind: String, val comment: String?, val type: String?)

/**
 * The declarations of a file, from the parse tree of ForgeParser and the text it was parsed from: the outline
 * (the structure view of IntelliJ, the document symbols of a language server) and their documentation.
 */
class ForgeDeclarations(private val text: String, private val tree: ForgeParser.RootContext) {

    /** Every name the file declares, in the order of the text. */
    val names: List<ForgeDeclaredName> by lazy {
        buildList {
            fun visit(node: ParseTree) {
                if (node is TerminalNode) {
                    val context = node.parent as? ParserRuleContext ?: return
                    val token = node.symbol
                    if (token.startIndex < 0 || token.tokenIndex < 0) return
                    val role = nameRole(context, token) ?: return
                    if (isDeclaration(context, token)) add(ForgeDeclaredName(token, context, role))
                    return
                }
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(tree)
        }
    }

    /** The declared name at [offset] (from its start to its end), if any. */
    fun nameAt(offset: Int): ForgeDeclaredName? = names.firstOrNull { offset in it.start..it.end }

    /** The declared name that starts at [line] (from 1) and [column] (from 0), as the compiler's spans say. */
    fun nameAt(line: Int, column: Int): ForgeDeclaredName? =
        names.firstOrNull { it.token.line == line && it.token.charPositionInLine == column }

    // ---- the outline -----------------------------------------------------------------------------------------

    /**
     * The declarations of the file: the names that the children of the Root rule declare; a module (`mod m { ... }`)
     * with its declarations, a trait with its members.
     */
    fun outline(): List<ForgeOutlineEntry> = outline(tree)

    private fun outline(parent: ParserRuleContext): List<ForgeOutlineEntry> = parent.children.orEmpty().filterIsInstance<ParserRuleContext>().mapNotNull { decl ->
        val name = names.firstOrNull { it.context === decl } ?: return@mapNotNull null
        val children = when {
            decl is ForgeParser.ModDefContext -> outline(decl)
            decl is ForgeParser.TraitDefContext -> names.filter { it.context.parent === decl }
                .map { ForgeOutlineEntry(it, oneLine(textOf(it.context)), it.context.startOffset, it.context.endOffset, emptyList()) }
            name.role == ForgeNameRole.NODE_DECLARATION || name.role == ForgeNameRole.TYPE_DECLARATION ->
                names.filter { it !== name && it.context.parent === decl && isMember(it) }
                    .map { ForgeOutlineEntry(it, oneLine(textOf(it.context)), it.context.startOffset, it.context.endOffset, emptyList()) }
            else -> emptyList()
        }
        ForgeOutlineEntry(
            name, header(decl, structureStop, 100), decl.startOffset, decl.endOffset, children,
            isEnum = decl is ForgeParser.EnumContext,
            isInterface = decl is ForgeParser.NodeDefContext && decl.LPAREN() == null,
        )
    }

    /** A field of a record, a constant of an enum, a child of a node. */
    private fun isMember(name: ForgeDeclaredName): Boolean =
        name.role == ForgeNameRole.FIELD || name.role == ForgeNameRole.ENUM_CONSTANT || isNodeChild(name)

    /** A declaration in the parameters of a node is a child (a field of the node), elsewhere a parameter. */
    fun isNodeChild(name: ForgeDeclaredName): Boolean =
        name.role == ForgeNameRole.PARAMETER && name.context.parent is ForgeParser.NodeDefContext

    // ---- documentation ---------------------------------------------------------------------------------------

    /**
     * The documentation of the declaration [name]; [type] is its type if the analysis knows it (locals and
     * parameters, see [com.leko.forge.ide.semantics.ForgeSemantics.declarationTypes]).
     */
    fun info(name: ForgeDeclaredName, type: String? = null): ForgeDeclarationInfo? {
        val decl = topLevel(name.context) ?: return null
        val kind = when (name.role) {
            ForgeNameRole.NODE_DECLARATION -> if (textOf(decl).contains("(")) "node" else "interface node"
            ForgeNameRole.TYPE_DECLARATION -> if (decl is ForgeParser.EnumContext) "enum" else "record"
            ForgeNameRole.TYPE_PARAMETER -> "type parameter of ${header(decl, documentationStop, 160)}"
            ForgeNameRole.ENUM_CONSTANT -> "enum constant"
            ForgeNameRole.FUNCTION_DECLARATION -> if (textOf(decl).startsWith("ext")) "external function" else "function"
            ForgeNameRole.PROPERTY -> "property (computed at each use)"
            ForgeNameRole.CONSTANT -> "constant"
            ForgeNameRole.FIELD -> "field"
            ForgeNameRole.PARAMETER -> if (decl is ForgeParser.NodeDefContext) "child" else "parameter"
            ForgeNameRole.LOCAL_VARIABLE -> "variable"
            ForgeNameRole.ATTRIBUTE -> when (decl) {
                is ForgeParser.SynDefContext -> "synthesized attribute"
                is ForgeParser.InhDefContext -> "inherited attribute"
                is ForgeParser.CircularDefContext -> "circular attribute"
                is ForgeParser.CollContext -> "collection attribute"
                else -> "attribute"
            }
            ForgeNameRole.PORT -> "port"
            ForgeNameRole.GRAPH -> "graph"
            ForgeNameRole.PARSER -> "parser"
            ForgeNameRole.PARSER_RULE -> "parser rule"
            ForgeNameRole.MODULE -> "module"
            ForgeNameRole.TRAIT -> "trait"
            else -> "name"
        }
        val owner = header(decl, documentationStop, 160)
        // a part of a declaration: a parameter, a field, an enum constant, a local, ...
        val part = generateSequence(name.context) { it.parent as? ParserRuleContext }.takeWhile { it !== decl }.firstOrNull {
            it is ForgeParser.ParameterContext || it is ForgeParser.RecordFieldContext || it is ForgeParser.EnumItemContext ||
                it is ForgeParser.TraitMemberContext
        }
        return when {
            part != null -> ForgeDeclarationInfo(oneLine(textOf(part)), "$kind of $owner", null, null)
            name.context === decl -> ForgeDeclarationInfo(header(decl, documentationStop, 160), kind, docComment(decl), null)
            else -> ForgeDeclarationInfo(name.name, "$kind in $owner", null, type)
        }
    }

    /** The doc comment (`/** ... */`) right before [decl], with only whitespace between them. */
    private fun docComment(decl: ParserRuleContext): String? {
        val lexer = ForgeEditorLexer(text, 0, decl.startOffset)
        var comment: String? = null
        while (true) {
            val type = lexer.tokenType ?: break
            when (type) {
                ForgeLexer.WS -> {}
                ForgeLexer.BLOCK_COMMENT -> comment = text.substring(lexer.tokenStart, lexer.tokenEnd)
                else -> comment = null
            }
            lexer.advance()
        }
        return comment?.takeIf { it.startsWith("/**") && it.endsWith("*/") }
    }

    // ---- the text of declarations ----------------------------------------------------------------------------

    private val ParserRuleContext.startOffset: Int get() = start?.startIndex?.coerceAtLeast(0) ?: 0
    private val ParserRuleContext.endOffset: Int
        get() = stop?.takeIf { it.type != Token.EOF && it.stopIndex >= 0 }?.let { it.stopIndex + 1 }?.coerceAtLeast(startOffset) ?: startOffset

    /** The text of [context] as it is in the file (ANTLR's `getText` leaves out whitespace and comments). */
    private fun textOf(context: ParserRuleContext): String = text.substring(context.startOffset, context.endOffset.coerceAtMost(text.length))

    private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /** The declaration [context] is in: a child of the Root rule or of a module (a module for its own name). */
    private fun topLevel(context: ParserRuleContext): ParserRuleContext? =
        generateSequence(context) { it.parent as? ParserRuleContext }.firstOrNull {
            it.parent is ForgeParser.RootContext || (it.parent is ForgeParser.ModDefContext && it !is ForgeParser.ModDefContext) ||
                (it is ForgeParser.ModDefContext && it === context)
        }

    /** The token a declaration's header ends before, for the documentation: before the value or the body. */
    private val documentationStop: (ParserRuleContext, Token) -> Boolean = { decl, token ->
        when (decl) {
            is ForgeParser.ParseRuleDefContext -> token.type == ForgeLexer.ARROW
            is ForgeParser.RecordContext, is ForgeParser.EnumContext, is ForgeParser.ConstDefContext -> false
            is ForgeParser.InhImplContext, is ForgeParser.FlowDefContext, is ForgeParser.ModDefContext, is ForgeParser.TraitDefContext ->
                token.type == ForgeLexer.LBRACE
            else -> token.type == ForgeLexer.ASSIGN
        }
    }

    /** The token a declaration's header ends before, for the outline. */
    private val structureStop: (ParserRuleContext, Token) -> Boolean = { decl, token ->
        token.type == ForgeLexer.ASSIGN || (token.type == ForgeLexer.ARROW && decl is ForgeParser.ParseRuleDefContext) ||
            (token.type == ForgeLexer.LBRACE && decl !is ForgeParser.RecordContext && decl !is ForgeParser.EnumContext)
    }

    /** The text of [decl] up to its body, on one line, at most [limit] characters. */
    private fun header(decl: ParserRuleContext, stop: (ParserRuleContext, Token) -> Boolean, limit: Int): String {
        val stopToken = decl.children.orEmpty().filterIsInstance<TerminalNode>().map { it.symbol }.firstOrNull { stop(decl, it) }
        val end = stopToken?.startIndex ?: decl.endOffset
        val header = oneLine(text.substring(decl.startOffset, end.coerceIn(decl.startOffset, text.length)))
        return if (header.length > limit) header.take(limit - 3) + "..." else header
    }
}
