package com.leko.forge.intellij.completion

import ForgeLexer
import ForgeParser
import com.leko.forge.attr.Resolve
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.symbol
import com.leko.forge.attr.typeOrNull
import com.leko.forge.intellij.parser.ForgeNameRole
import com.leko.forge.intellij.parser.ForgePsiParser
import com.leko.forge.intellij.parser.isDeclaration
import com.leko.forge.intellij.parser.nameRole
import com.leko.forge.intellij.semantics.ForgeAnalysis
import com.leko.forge.intellij.semantics.ForgeSemantics
import com.leko.forge.model.*
import com.leko.forge.types.ErrorType
import com.leko.forge.types.FunctionType
import com.leko.forge.types.OptionalType
import com.leko.forge.types.Type
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import java.io.File

/** What a completion is. */
enum class ForgeCandidateKind {
    KEYWORD, NODE, INTERFACE_NODE, RECORD, ENUM, BUILTIN_TYPE, ENUM_CONSTANT, FIELD, ATTRIBUTE, PROPERTY,
    METHOD, FUNCTION, CONSTANT, PARAMETER, LOCAL_VARIABLE, PORT, GRAPH, PARSER, PARSER_RULE, ANNOTATION,
}

/**
 * A completion: [name] to insert, [type] and [tail] (parameters) to show; [call] if it is called, so that
 * parentheses follow it ([hasParameters]: the caret goes between them).
 */
data class ForgeCandidate(
    val name: String,
    val kind: ForgeCandidateKind,
    val type: String? = null,
    val tail: String? = null,
    val call: Boolean = false,
    val hasParameters: Boolean = false,
)

/**
 * The completions at [offset] of [text], the text of the file with IntelliJ's dummy identifier at the caret. The
 * program is analyzed with the compiler: members after a `.` are those the compiler resolves for the type of the
 * receiver (fields, attributes, vals, methods, enum constants, rules), names are the declarations of the program
 * and the locals in scope, and keywords fit where they are.
 */
class ForgeCompletion(private val text: String, private val offset: Int, private val file: File?, private val read: (File) -> CharStream) {

    fun candidates(): List<ForgeCandidate> {
        // the program as it is, with the dummy identifier: what an expression is, what a receiver's type is
        ForgeSemantics.analyze(text, file, read) { analysis -> complete(analysis, full = true) }?.let { return it }
        // an incomplete declaration can not be analyzed: the rest of the program can, without it
        val (start, end) = declarationRange()
        val blanked = text.substring(0, start) + text.substring(start, end).replace(Regex("[^\\n]"), " ") + text.substring(end)
        val globals = ForgeSemantics.analyze(blanked, file, read) { analysis -> Globals(analysis) }
        return complete(null, full = false, globals)
    }

    // ---- the place of the caret ------------------------------------------------------------------------------

    private val tokens: List<Token> = ForgePsiParser.antlrTokens(text)

    /** The token at the caret (the dummy identifier, with what was typed before it) and its index. */
    private val caretIndex: Int = tokens.indexOfFirst { it.type != Token.EOF && it.startIndex <= offset && offset <= it.stopIndex + 1 && it.type == ForgeLexer.IDENT }
        .takeIf { it >= 0 } ?: tokens.indexOfFirst { it.startIndex >= offset }.coerceAtLeast(0)

    private fun tokenType(relative: Int): Int = tokens.getOrNull(caretIndex + relative)?.type ?: Token.INVALID_TYPE

    private fun tokenText(relative: Int): String? = tokens.getOrNull(caretIndex + relative)?.text

    /** The lines of the top level declaration of the caret: from a line starting with a letter to the next one. */
    private fun declarationRange(): Pair<Int, Int> {
        val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        fun startsDeclaration(lineStart: Int) = lineStart < text.length && (text[lineStart].isLetter() || text[lineStart] == '@')
        val caretLine = lineStarts.indexOfLast { it <= offset }
        val start = (caretLine downTo 0).map { lineStarts[it] }.firstOrNull(::startsDeclaration) ?: 0
        val end = lineStarts.drop(caretLine + 1).firstOrNull(::startsDeclaration) ?: text.length
        return start to end
    }

    /** The terminal of the caret's token in [tree], with its parents. */
    private fun terminal(tree: ParseTree): TerminalNode? {
        val token = tokens.getOrNull(caretIndex) ?: return null
        fun find(node: ParseTree): TerminalNode? {
            if (node is TerminalNode) return node.takeIf { it.symbol.startIndex == token.startIndex && it.symbol.type == token.type }
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        return find(tree)
    }

    // ---- the declarations of the program ---------------------------------------------------------------------

    /** The declarations of the analyzed program, collected while its attributes are there. */
    private class Globals(analysis: ForgeAnalysis) {
        val symTab = SymTab.instance(analysis.context)
        val typeMap = TypeMap.instance(analysis.context)
        val types: List<Symbol> = symTab.table.values.filter { it.kind == Kind.TYP && it.span != null && it !is ParserSymbol }
        val nodes: List<NodeSymbol> = types.filterIsInstance<NodeSymbol>()
        val functions: List<FunctionSymbol> = symTab.functions.values.filter { it.receiver == null }
        val constants: List<ConstSymbol> = symTab.constants.values.toList()
        val ports: List<PortSymbol> = symTab.ports.values.toList()
        val graphs: List<FlowGraphSymbol> = symTab.graphs.values.toList()
        val parsers: List<ParserSymbol> = symTab.parsers.values.toList()
        val inherited: List<InhSymbol> = symTab.inheritedAttributes.values.toList()
        val rules: Map<String, List<ParserRuleSymbol>> = parsers.associate { p ->
            p.name to (typeMap.parserRuleDefs[p].orEmpty() + typeMap.parserRuleImpls[p].orEmpty()).distinctBy { it.name }
        }

        /** The attributes a node has from its interfaces: what `syn Node.` implements. */
        fun inheritedAttributesOf(name: String): List<NodeBoundAttributeSymbol> {
            val node = nodes.firstOrNull { it.name == name } ?: return emptyList()
            return (typeMap.allParentsOf(node) + node).flatMap { typeMap.nodeAttributes[it].orEmpty() }.distinctBy { it.name }
        }
    }

    private fun complete(analysis: ForgeAnalysis?, full: Boolean, given: Globals? = null): List<ForgeCandidate> {
        val globals = given ?: analysis?.let(::Globals)
        val tree = analysis?.main ?: ForgePsiParser.parseAntlr(text).first
        val terminal = terminal(tree)
        val context = terminal?.parent as? ParserRuleContext
        val token = terminal?.symbol

        // `syn Node.|`, `inh Node.|`, `circular(..) Node.|`, `rule Parser.|`: the names they may define
        if (tokenType(-1) == ForgeLexer.DOT && tokenType(-2) == ForgeLexer.IDENT) {
            val owner = tokenText(-2)!!
            val keyword = (caretIndex - 3 downTo 0).map { tokens[it] }.firstOrNull { it.charPositionInLine == 0 }?.type
            val declaring = tokens.getOrNull(caretIndex - 3)?.let { it.type == ForgeLexer.SYN || it.type == ForgeLexer.INH || it.type == ForgeLexer.RULE || it.type == ForgeLexer.RPAREN } == true
            if (declaring && globals != null) {
                return when (keyword) {
                    ForgeLexer.SYN, ForgeLexer.CIRCULAR -> globals.inheritedAttributesOf(owner).map { attribute(it) }
                    ForgeLexer.INH -> globals.inherited.map { attribute(it) }
                    ForgeLexer.RULE -> globals.rules[owner].orEmpty().map { rule(it) }
                    else -> emptyList()
                }
            }
        }

        // members: after `.` in an expression
        if (full && analysis != null && context != null && token != null && tokenType(-1) == ForgeLexer.DOT) {
            when (context) {
                is ForgeParser.FieldAccessContext -> if (token === context.name) return members(analysis, context.receiver)
                is ForgeParser.MethodCallContext -> if (token === context.name) return members(analysis, context.receiver)
                is ForgeParser.TypePatternContext -> if (token === context.name) {
                    val enum = globals?.types?.firstOrNull { it.name == context.receiver.text } as? EnumSymbol
                    return enum?.items.orEmpty().map(::enumItem)
                }
                is ForgeParser.ContribContext -> if (token === context.collName) {
                    val type = context.receiver.typeOrNull ?: return emptyList()
                    val node = globals?.symTab?.get(type) as? NodeSymbol ?: return emptyList()
                    return globals.typeMap.nodeAttributes[node].orEmpty().filterIsInstance<CollSymbol>().map { attribute(it) }
                }
                else -> {}
            }
        }
        if (tokenType(-1) == ForgeLexer.DOT) {
            // a type as receiver: the constants of an enum, the rules of a parser
            val owner = tokenText(-2)
            val type = globals?.types?.firstOrNull { it.name == owner }
            if (type is EnumSymbol) return type.items.map(::enumItem)
            globals?.rules?.get(owner)?.let { rules -> return rules.map { rule(it) } }
            return emptyList()
        }

        // `@...`: the primitives of binary parsers, or the operators of abstract rules
        if (tokenType(-1) == ForgeLexer.AT) {
            // after the `->` of a rule: its alternatives; before it: its operator
            val ruleStart = (caretIndex - 1 downTo 0).firstOrNull { tokens[it].type == ForgeLexer.RULE && tokens[it].charPositionInLine == 0 }
            val inAlternatives = ruleStart != null && (ruleStart until caretIndex).any { tokens[it].type == ForgeLexer.ARROW }
            val names = if (inAlternatives) listOf("u1", "u2", "u4", "bit", "uint", "sint", "bytes") else listOf("prefix", "infixLeft", "infixRight", "postfix")
            return names.map { ForgeCandidate(it, ForgeCandidateKind.ANNOTATION, call = it in setOf("uint", "sint", "bytes", "prefix", "infixLeft", "infixRight", "postfix"), hasParameters = true) }
        }

        // the start of a top level declaration
        val atLineStart = tokens.getOrNull(caretIndex)?.charPositionInLine == 0
        // (declarations start at the start of a line; what continues one is indented)
        if (atLineStart) {
            return TOP_LEVEL_KEYWORDS.map(::keyword)
        }

        // after the keywords of declarations: what they name
        when (tokenType(-1)) {
            ForgeLexer.SYN, ForgeLexer.COLL, ForgeLexer.CONTRIB -> return globals?.nodes.orEmpty().map(::type)
            ForgeLexer.IMPL -> return globals?.nodes.orEmpty().filterIsInstance<InterfaceNodeSymbol>().map(::type)
            ForgeLexer.FLOW -> return globals?.graphs.orEmpty().map { ForgeCandidate(it.name, ForgeCandidateKind.GRAPH) }
            ForgeLexer.RULE -> return globals?.parsers.orEmpty().map { ForgeCandidate(it.name, ForgeCandidateKind.PARSER) }
            ForgeLexer.FOR -> if (tokens.getOrNull(caretIndex - 1)?.charPositionInLine == 0) return globals?.nodes.orEmpty().map(::type)
            ForgeLexer.RPAREN -> if (context is ForgeParser.CircularDefContext && token === context.node) return globals?.nodes.orEmpty().map(::type)
            ForgeLexer.COLON -> {
                // `graph g: Node`, `flow g: Node`
                val first = (caretIndex - 1 downTo 0).map { tokens[it] }.firstOrNull { it.charPositionInLine == 0 }?.type
                if (first == ForgeLexer.GRAPH || first == ForgeLexer.FLOW) return globals?.nodes.orEmpty().map(::type)
            }
        }

        // types
        if (context is ForgeParser.NamedTypeContext || context is ForgeParser.NodeDefContext && token === context.parent) {
            val all = listOf("int", "float", "bool", "str", "Nothing").map { ForgeCandidate(it, ForgeCandidateKind.BUILTIN_TYPE) } +
                listOf("List", "Set", "Map").map(::keyword) + globals?.types.orEmpty().map(::type) + typeParameters(context)
            return if (context is ForgeParser.NodeDefContext) globals?.nodes.orEmpty().filterIsInstance<InterfaceNodeSymbol>().map(::type) else all
        }

        // the nonterminals of a rule: the rules of its parser
        if (context is ForgeParser.NonterminalRuleContext || context is ForgeParser.ParserLabeledContext) {
            val rule = generateSequence(context) { it.parent as? ParserRuleContext }.filterIsInstance<ForgeParser.ParseRuleDefContext>().firstOrNull()
            val parser = rule?.parserName?.text
            return globals?.rules?.get(parser).orEmpty().map { rule(it) } + locals(tree)
        }

        // an expression: locals, constants, functions, types, and the keywords of expressions
        val inFlow = generateSequence(context) { it.parent as? ParserRuleContext }.any { it is ForgeParser.FlowDefContext }
        return buildList {
            addAll(locals(tree))
            globals?.let { g ->
                g.constants.forEach { add(ForgeCandidate(it.name, ForgeCandidateKind.CONSTANT, it.type.display())) }
                g.functions.forEach { add(function(it, ForgeCandidateKind.FUNCTION)) }
                g.types.forEach { t ->
                    when (t) {
                        is ConcreteNodeSymbol -> add(ForgeCandidate(t.name, ForgeCandidateKind.NODE, tail = params(t.fields), call = true, hasParameters = t.fields.isNotEmpty()))
                        is RecordSymbol -> add(ForgeCandidate(t.name, ForgeCandidateKind.RECORD, tail = params(t.fields), call = !t.opaque, hasParameters = t.fields.isNotEmpty()))
                        else -> add(type(t))
                    }
                }
                g.graphs.forEach { add(ForgeCandidate(it.name, ForgeCandidateKind.GRAPH)) }
                g.parsers.forEach { add(ForgeCandidate(it.name, ForgeCandidateKind.PARSER)) }
                if (inFlow) {
                    g.ports.forEach { add(ForgeCandidate(it.name, ForgeCandidateKind.PORT)) }
                    listOf("entry", "sequence", "bound").forEach { add(ForgeCandidate(it, ForgeCandidateKind.FUNCTION, call = true, hasParameters = true)) }
                }
            }
            addAll((if (inFlow) EXPRESSION_KEYWORDS + FLOW_KEYWORDS else EXPRESSION_KEYWORDS).map(::keyword))
        }.distinctBy { it.name to it.kind }
    }

    /** The members the compiler resolves for the type of [receiver]. */
    private fun members(analysis: ForgeAnalysis, receiver: ForgeParser.ExpressionContext): List<ForgeCandidate> {
        val resolve = Resolve.instance(analysis.context)
        val globals = Globals(analysis)
        val receiverSymbol = (receiver as? ForgeParser.IdentifierContext)?.symbol
        val (type, receiverKind) = if (receiverSymbol != null && receiverSymbol.kind == Kind.TYP) receiverSymbol.type to Kind.TYP
            else (receiver.typeOrNull ?: return emptyList()) to Kind.VAL
        if (type == ErrorType || type is OptionalType) return emptyList()
        // every name a member can have; the compiler decides which are members of this type
        val names = buildSet {
            globals.nodes.filterIsInstance<ConcreteNodeSymbol>().forEach { n -> n.fields.forEach { add(it.name) } }
            globals.types.filterIsInstance<RecordSymbol>().forEach { r -> r.fields.forEach { add(it.name) } }
            globals.types.filterIsInstance<EnumSymbol>().forEach { e -> e.items.forEach { add(it.name) } }
            globals.typeMap.nodeAttributes.values.forEach { a -> a.forEach { add(it.name) } }
            globals.inherited.forEach { add(it.name) }
            globals.typeMap.methods.values.forEach { m -> m.forEach { add(it.name) } }
            globals.typeMap.genericMethods.forEach { add(it.name) }
            globals.typeMap.stdMethods.forEach { add(it.name) }
            globals.rules.values.forEach { r -> r.forEach { add(it.name) } }
            globals.graphs.forEach { g -> g.methods.forEach { add(it.name) } }
        }
        val onThis = receiver is ForgeParser.ThisExprContext
        return names.flatMap { name ->
            listOf(Kind.VAL, Kind.MTH).mapNotNull { memberKind ->
                val symbol = runCatching { resolve.resolve(type, name, receiverKind, memberKind) }.getOrNull() ?: return@mapNotNull null
                // inherited attributes are read on `this` only
                if (symbol is InhSymbol && !onThis) return@mapNotNull null
                member(symbol)
            }
        }.distinctBy { it.name to it.call }.sortedBy { it.kind.ordinal }
    }

    private fun member(symbol: Symbol): ForgeCandidate? = when (symbol) {
        is VarSymbol -> ForgeCandidate(symbol.name, ForgeCandidateKind.FIELD, symbol.type.display())
        is AttributeSymbol -> attribute(symbol)
        is FunctionSymbol -> if (symbol.isProperty) ForgeCandidate(symbol.name, ForgeCandidateKind.PROPERTY, symbol.returnType.display())
            else function(symbol, ForgeCandidateKind.METHOD)
        is EnumItemSymbol -> enumItem(symbol)
        is ParserRuleSymbol -> rule(symbol)
        else -> null
    }

    /** The locals in scope at the caret: the parameters, pattern variables, ... declared before it in its declaration. */
    private fun locals(tree: ParseTree): List<ForgeCandidate> {
        val (start, _) = declarationRange()
        val result = mutableListOf<ForgeCandidate>()
        fun visit(node: ParseTree) {
            if (node is TerminalNode) {
                val token = node.symbol
                val context = node.parent as? ParserRuleContext ?: return
                if (token.type != ForgeLexer.IDENT || token.startIndex < start || token.stopIndex >= offset) return
                if (!isDeclaration(context, token)) return
                when (nameRole(context, token)) {
                    ForgeNameRole.PARAMETER -> if (context.parent !is ForgeParser.NodeDefContext) {
                        result += ForgeCandidate(token.text, ForgeCandidateKind.PARAMETER, (context as? ForgeParser.ParameterContext)?.ty?.text)
                    }
                    ForgeNameRole.LOCAL_VARIABLE -> result += ForgeCandidate(token.text, ForgeCandidateKind.LOCAL_VARIABLE)
                    else -> {}
                }
                return
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(tree)
        return result.filter { it.name != DUMMY_PREFIX && !it.name.contains(DUMMY_PREFIX) }.distinctBy { it.name }
    }

    /** The type parameters of the function of the caret: `T` in `fun <T> List<T>.second(): T?`. */
    private fun typeParameters(context: ParserRuleContext?): List<ForgeCandidate> =
        generateSequence(context) { it.parent as? ParserRuleContext }.filterIsInstance<ForgeParser.FunctionContext>().firstOrNull()
            ?.typeParams.orEmpty().map { ForgeCandidate(it.text, ForgeCandidateKind.BUILTIN_TYPE) }

    private fun type(symbol: Symbol) = ForgeCandidate(symbol.name, when (symbol) {
        is ConcreteNodeSymbol -> ForgeCandidateKind.NODE
        is InterfaceNodeSymbol -> ForgeCandidateKind.INTERFACE_NODE
        is EnumSymbol -> ForgeCandidateKind.ENUM
        is FlowGraphSymbol -> ForgeCandidateKind.GRAPH
        else -> ForgeCandidateKind.RECORD
    })

    private fun attribute(symbol: AttributeSymbol) = ForgeCandidate(symbol.name, ForgeCandidateKind.ATTRIBUTE, symbol.type.display())

    private fun enumItem(item: EnumItemSymbol) = ForgeCandidate(
        item.name, ForgeCandidateKind.ENUM_CONSTANT, item.enumType,
        tail = item.parameters.takeIf { it.isNotEmpty() }?.joinToString(", ", "(", ")") { "${it.first}: ${it.second.display()}" },
        call = item.parameters.isNotEmpty(), hasParameters = item.parameters.isNotEmpty(),
    )

    private fun rule(rule: ParserRuleSymbol) = ForgeCandidate(rule.name, ForgeCandidateKind.PARSER_RULE, rule.returnType.display())

    private fun function(symbol: FunctionSymbol, kind: ForgeCandidateKind) = ForgeCandidate(
        symbol.name, kind, symbol.returnType.display(), params(symbol.params), call = true, hasParameters = symbol.params.isNotEmpty(),
    )

    private fun params(params: List<VarSymbol>) = params.joinToString(", ", "(", ")") { "${it.name}: ${it.type.display()}" }

    private fun Type.display(): String = if (this is FunctionType) "(${parameters.joinToString { it.display() }}) -> ${returnType.display()}" else toString()

    private fun keyword(name: String) = ForgeCandidate(name, ForgeCandidateKind.KEYWORD)

    companion object {
        /** IntelliJ's dummy identifier, which a local never declares. */
        const val DUMMY_PREFIX = "IntellijIdeaRulezzz"

        val TOP_LEVEL_KEYWORDS = listOf(
            "node", "record", "enum", "fun", "val", "ext", "op", "const", "syn", "inh", "circular", "coll", "contrib",
            "for", "parser", "rule", "port", "graph", "flow", "import",
        )
        val EXPRESSION_KEYWORDS = listOf("this", "if", "then", "else", "match", "true", "false", "null", "in", "as", "is", "for", "with", "where")
        val FLOW_KEYWORDS = listOf("vertex")
    }
}
