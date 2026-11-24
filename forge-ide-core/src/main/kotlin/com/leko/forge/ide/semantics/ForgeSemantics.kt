package com.leko.forge.ide.semantics

import ForgeParser
import com.leko.forge.analysis.Dependency
import com.leko.forge.attr.Attr
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.clearTreeAttributes
import com.leko.forge.attr.port
import com.leko.forge.attr.symbol
import com.leko.forge.attr.local
import com.leko.forge.attr.callee
import com.leko.forge.context.Context
import com.leko.forge.enter.Enter
import com.leko.forge.enter.MemberEnter
import com.leko.forge.ide.ForgeHost
import com.leko.forge.ide.parser.ForgeAntlr
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.ide.parser.nameRole
import com.leko.forge.ide.parser.typeParameterDeclaration
import com.leko.forge.model.*
import com.leko.forge.model.span
import com.leko.forge.parse.parseForgeImports
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import java.io.File
import java.util.logging.Level

/**
 * What a name refers to, as the compiler resolved it: the declaration at [line]:[column] (ANTLR's: lines from 1,
 * columns from 0) of [source] (`null`: the analyzed file; [com.leko.forge.parse.STD_SOURCE]: the standard library;
 * otherwise the path of an imported file), and the [role] its use has, e.g. [ForgeNameRole.ATTRIBUTE] for
 * `this.type` if `type` is a syn attribute.
 */
data class ForgeResolution(val name: String, val source: String?, val line: Int, val column: Int, val role: ForgeNameRole?)

/** An analyzed program: the tree of the analyzed file ([main], with its [tokens]) and of the whole program ([root]). */
class ForgeAnalysis(val context: Context, val main: ForgeParser.RootContext, val root: ForgeParser.RootContext, val tokens: List<Token>)

/**
 * A member of a trait that a method or property implements: [member] is where the member is declared; the type the
 * method is of ([type]) has all the members of [trait], so it satisfies `fun <T: Trait>`.
 */
data class ForgeTraitImplementation(val trait: String, val type: String, val member: ForgeResolution)

/** A declaration ([declaration]: a method, a property, an attribute or a field) of [type] that implements a member of a trait. */
data class ForgeTraitImplementer(val type: String, val label: String, val declaration: ForgeResolution)

/** A declaration related to another one: [resolution] is where it is; [abstract] if it has no implementation of its own. */
data class ForgeRelated(val resolution: ForgeResolution, val abstract: Boolean, val label: String)

/**
 * How a declaration is related to others: what it implements or overrides ([supers]), and what implements or
 * overrides it ([subs]). Nodes implement interface nodes; a syn or circular attribute of a node implements or
 * overrides the attribute of its interfaces; an `inh Node.attr { ... }` equation implements `inh attr`; a rule
 * with alternatives implements the abstract rule of the same name.
 */
data class ForgeHierarchy(val abstract: Boolean, val supers: List<ForgeRelated>, val subs: List<ForgeRelated>)

/**
 * Where the lines of an old text are in a new one, for [ForgeSemantics.moved]: lines from 1, as ANTLR's. `null` for
 * the lines that changed and for the offsets on them.
 */
interface ForgeLineMapping {
    /** The new line of [line]. */
    fun line(line: Int): Int?
    /** The new offset of [offset] of the old text. */
    fun offset(offset: Int): Int?
}

/**
 * The names of a file the compiler resolved, by the offset where they start, and the [hierarchy] of the
 * declarations of the file, by the offset of their names.
 */
class ForgeSemantics(
    val resolutions: Map<Int, ForgeResolution>,
    val hierarchy: Map<Int, ForgeHierarchy> = emptyMap(),
    /** The types of the used variables, parameters and constants of the file, by the line and column of their declaration. */
    val declarationTypes: Map<Pair<Int, Int>, String> = emptyMap(),
    /** The members of traits that the methods and properties of the file implement, by the offset of their names. */
    val traitImplementations: Map<Int, List<ForgeTraitImplementation>> = emptyMap(),
    /** The implementations of the members of the traits of the file (by the offset of their names), in the whole program. */
    val traitImplementers: Map<Int, List<ForgeTraitImplementer>> = emptyMap(),
) {

    /** These semantics in a changed text: what is on lines that are still there, moved with them; the rest is gone. */
    fun moved(map: ForgeLineMapping): ForgeSemantics {
        fun ForgeResolution.moved(): ForgeResolution? =
            if (source != null) this else map.line(line)?.let { copy(line = it) }
        fun ForgeRelated.moved(): ForgeRelated? = resolution.moved()?.let { copy(resolution = it) }
        return ForgeSemantics(
            resolutions = resolutions.entries.mapNotNull { (offset, r) ->
                val newOffset = map.offset(offset) ?: return@mapNotNull null
                r.moved()?.let { newOffset to it }
            }.toMap(),
            hierarchy = hierarchy.entries.mapNotNull { (offset, h) ->
                map.offset(offset)?.let { it to h.copy(supers = h.supers.mapNotNull { r -> r.moved() }, subs = h.subs.mapNotNull { r -> r.moved() }) }
            }.toMap(),
            declarationTypes = declarationTypes.entries.mapNotNull { (position, type) ->
                map.line(position.first)?.let { (it to position.second) to type }
            }.toMap(),
            traitImplementations = traitImplementations.entries.mapNotNull { (offset, list) ->
                val newOffset = map.offset(offset) ?: return@mapNotNull null
                val moved = list.mapNotNull { i -> i.member.moved()?.let { i.copy(member = it) } }
                newOffset to moved
            }.filter { it.second.isNotEmpty() }.toMap(),
            traitImplementers = traitImplementers.entries.mapNotNull { (offset, list) ->
                val newOffset = map.offset(offset) ?: return@mapNotNull null
                val moved = list.mapNotNull { i -> i.declaration.moved()?.let { i.copy(declaration = it) } }
                newOffset to moved
            }.filter { it.second.isNotEmpty() }.toMap(),
        )
    }

    companion object {
        /** The compiler keeps the attributes of the trees in one global table, so analyses run one at a time. */
        val lock = Any()

        /** No resolved names: for a file that can not be analyzed. */
        val EMPTY = ForgeSemantics(emptyMap())

        private fun semanticsOf(analysis: ForgeAnalysis): ForgeSemantics {
            val collector = Collector(analysis.context).apply { visit(analysis.main) }
            return ForgeSemantics(
                collector.resolutions, Hierarchy(analysis.context, analysis.root).compute(), collector.declarationTypes,
                traitImplementations(analysis), traitImplementers(analysis),
            )
        }

        /**
         * For each member of a trait of the analyzed file, its implementations in the whole program: of every type
         * that has all the members of the trait, the declaration the compiler takes for the member.
         */
        private fun traitImplementers(analysis: ForgeAnalysis): Map<Int, List<ForgeTraitImplementer>> {
            val traits = com.leko.forge.attr.Traits.instance(analysis.context)
            val symTab = SymTab.instance(analysis.context)
            val typeMap = TypeMap.instance(analysis.context)
            val resolve = com.leko.forge.attr.Resolve.instance(analysis.context)
            // the types that may have a member: receivers of methods, owners of attributes and fields of that name
            fun candidates(name: String): List<com.leko.forge.types.Type> = buildList {
                analysis.root.function().forEach { f -> val sym = f.symbol; if (sym?.name == name) sym.receiver?.type?.let { add(it) } }
                typeMap.nodeAttributes.forEach { (owner, attributes) -> if (attributes.any { it.name == name }) add(owner.type) }
                symTab.table.values.forEach { t ->
                    val fields = when (t) { is ConcreteNodeSymbol -> t.fields; is RecordSymbol -> if (t.span != null) t.fields else emptyList(); else -> emptyList() }
                    if (fields.any { it.name == name }) add(t.type)
                }
            }.distinct()
            fun label(type: com.leko.forge.types.Type, sym: Symbol): String = when (sym) {
                is FunctionSymbol -> (if (sym.isOperator) "op " else "") + (if (sym.isProperty) "val " else "fun ") + "$type.${sym.name}"
                is AttributeSymbol -> "attribute $type.${sym.name}"
                else -> "field $type.${sym.name}"
            }
            val result = mutableMapOf<Int, List<ForgeTraitImplementer>>()
            fun traitDefs(t: ParseTree): List<ForgeParser.TraitDefContext> = when (t) {
                is ForgeParser.TraitDefContext -> listOf(t)
                is ForgeParser.RootContext, is ForgeParser.ModDefContext -> (0 until t.childCount).flatMap { traitDefs(t.getChild(it)) }
                else -> emptyList()
            }
            for (traitDef in traitDefs(analysis.main)) {
                val trait = symTab.traits[traitDef.name.text] ?: continue
                for ((member, ctx) in trait.members.zip(traitDef.traitMember())) {
                    if (ctx.name.startIndex < 0) continue
                    val implementers = candidates(member.name).mapNotNull { type ->
                        val sym = traits.implementations(type, trait)?.get(member) ?: return@mapNotNull null
                        val declaration = (sym as? FunctionSymbol)?.declaration ?: sym
                        val span = declaration.span ?: return@mapNotNull null
                        if (span.startLine <= 0) return@mapNotNull null
                        ForgeTraitImplementer(type.toString(), label(type, declaration),
                            ForgeResolution(declaration.name, span.source, span.startLine, span.startCol, null))
                    }.distinctBy { it.declaration }
                    if (implementers.isNotEmpty()) result[ctx.name.startIndex] = implementers
                }
            }
            return result
        }

        /**
         * The methods and properties of the analyzed file that implement a member of a trait: of a type that has
         * every member of the trait (by the rules of bounds), the one the compiler takes for this member.
         */
        private fun traitImplementations(analysis: ForgeAnalysis): Map<Int, List<ForgeTraitImplementation>> {
            val traits = com.leko.forge.attr.Traits.instance(analysis.context)
            val symTab = SymTab.instance(analysis.context)
            val result = mutableMapOf<Int, List<ForgeTraitImplementation>>()
            fun functions(t: ParseTree): List<ForgeParser.FunctionContext> = when (t) {
                is ForgeParser.FunctionContext -> listOf(t)
                is ForgeParser.RootContext, is ForgeParser.ModDefContext -> (0 until t.childCount).flatMap { functions(t.getChild(it)) }
                else -> emptyList()
            }
            for (function in functions(analysis.main)) {
                val sym = function.symbol ?: continue
                val receiver = sym.receiver?.type ?: continue
                if (function.name.startIndex < 0) continue
                val implemented = symTab.traits.values.flatMap { trait ->
                    val members = trait.members.filter { it.name == sym.name && it.isProperty == sym.isProperty }
                    if (members.isEmpty()) return@flatMap emptyList()
                    val all = traits.implementations(receiver, trait) ?: return@flatMap emptyList()
                    members.filter { m -> (all[m] as? FunctionSymbol)?.declaration === sym }.map { m ->
                        ForgeTraitImplementation(
                            com.leko.forge.types.displayName(trait.name), receiver.toString(),
                            ForgeResolution(m.name, m.span.source, m.span.startLine, m.span.startCol,
                                if (m.isProperty) ForgeNameRole.PROPERTY else ForgeNameRole.FUNCTION_DECLARATION),
                        )
                    }
                }
                if (implemented.isNotEmpty()) result[function.name.startIndex] = implemented
            }
            return result
        }

        /**
         * Analyzes [text], the text of [file] (`null` if it is not on disk; then its imports can not be found),
         * with the compiler, and records what its names resolve to. Imported files are read by [host]. Empty if
         * the analysis is not possible, e.g. for a missing import, or fails on an incomplete tree.
         */
        fun compute(text: String, file: File?, host: ForgeHost): ForgeSemantics = computeOrNull(text, file, host) ?: EMPTY

        /** The semantics of [text] (see [compute]); `null` if it can not be analyzed. */
        fun computeOrNull(text: String, file: File?, host: ForgeHost): ForgeSemantics? = analyze(text, file, host, ::semanticsOf)

        /**
         * Runs the compiler's analysis on [text] (see [compute]) and then [block], while the attributes of the
         * trees are there; `null` if the analysis is not possible or fails.
         */
        fun <T> analyze(text: String, file: File?, host: ForgeHost, block: (ForgeAnalysis) -> T): T? {
            val tokens = ForgeAntlr.tokens(text)
            val parser = ForgeParser(CommonTokenStream(ListTokenSource(tokens)))
            parser.removeErrorListeners()
            val main = parser.root()
        // the expressions in strings, and `where`s as the compiler sees them
        com.leko.forge.parse.postParse(main, object : org.antlr.v4.runtime.BaseErrorListener() {})
            if (file == null && main.importDecl().any { it.path.text != "\"std\"" }) return null
            return synchronized(lock) {
                try {
                    val root = (file ?: File("<editor>")).parseForgeImports(main, host::read)
                    val context = Context()
                    com.leko.forge.enter.Modules.instance(context).resolve(root)
                    Enter.instance(context).enter(root)
                    MemberEnter.instance(context).enter(root)
                    Attr.instance(context).attr(root)
                    Dependency.instance(context).check(root)
                    block(ForgeAnalysis(context, main, root, tokens))
                } catch (e: Throwable) {
                    if (host.cancels(e)) throw e
                    // the analysis needs a complete tree; while the file is edited, it may not be
                    if (parser.numberOfSyntaxErrors == 0) host.log(Level.FINE, "the Forge analysis failed", e)
                    null
                } finally {
                    clearTreeAttributes()
                }
            }
        }
    }

    /** The [ForgeHierarchy] of the declarations of the analyzed file, from the declarations of the whole program. */
    private class Hierarchy(context: Context, private val root: ForgeParser.RootContext) {
        private val symTab = SymTab.instance(context)
        private val typeMap = TypeMap.instance(context)

        private enum class Kind { NODE, ATTRIBUTE, INH_DEFINITION, INH_EQUATION, RULE }

        private class Decl(val kind: Kind, val token: Token, val owner: NodeSymbol?, val group: String, val abstract: Boolean, val label: String)

        private fun node(name: String?): NodeSymbol? =
            symTab.table.values.firstOrNull { it is NodeSymbol && it.name == name } as NodeSymbol?

        private fun declarations(): List<Decl> = buildList {
            for (def in root.nodeDef()) {
                val sym = def.symbol ?: node(def.name.text) ?: continue
                add(Decl(Kind.NODE, def.name, sym, "", sym is InterfaceNodeSymbol, "node ${def.name.text}"))
            }
            for (def in root.synDef()) {
                val owner = node(def.node?.text) ?: continue
                add(Decl(Kind.ATTRIBUTE, def.attrName, owner, def.attrName.text, def.syn == null, "syn ${def.node.text}.${def.attrName.text}"))
            }
            for (def in root.circularDef()) {
                val owner = node(def.node?.text) ?: continue
                add(Decl(Kind.ATTRIBUTE, def.attrName, owner, def.attrName.text, false, "circular ${def.node.text}.${def.attrName.text}"))
            }
            for (def in root.inhDef()) add(Decl(Kind.INH_DEFINITION, def.attrName, null, def.attrName.text, true, "inh ${def.attrName.text}"))
            for (impl in root.inhImpl()) {
                add(Decl(Kind.INH_EQUATION, impl.attrName, node(impl.node?.text), impl.attrName.text, false, "inh ${impl.node.text}.${impl.attrName.text}"))
            }
            for (rule in root.parseRuleDef()) {
                add(Decl(Kind.RULE, rule.ruleName, null, "${rule.parserName.text}.${rule.ruleName.text}", rule.ARROW() == null,
                    "rule ${rule.parserName.text}.${rule.ruleName.text}: ${rule.returnType.text}"))
            }
        }.filter { it.token.startIndex >= 0 }

        /** Whether [sub] is below [sup] in the hierarchy of nodes. */
        private fun below(sub: NodeSymbol, sup: NodeSymbol): Boolean = sub !== sup && sup in typeMap.allParentsOf(sub)

        private fun related(decl: Decl) = ForgeRelated(
            ForgeResolution(decl.token.text, decl.token.span.source, decl.token.line, decl.token.charPositionInLine, null),
            decl.abstract, decl.label,
        )

        fun compute(): Map<Int, ForgeHierarchy> {
            val decls = declarations()
            val result = mutableMapOf<Int, ForgeHierarchy>()
            for (decl in decls) {
                // only the declarations of the analyzed file: its tokens have no source
                if (decl.token.span.source != null) continue
                val others = decls.filter { it !== decl }
                val (supers, subs) = when (decl.kind) {
                    Kind.NODE -> others.filter { it.kind == Kind.NODE && below(decl.owner!!, it.owner!!) } to
                        others.filter { it.kind == Kind.NODE && below(it.owner!!, decl.owner!!) }
                    Kind.ATTRIBUTE -> {
                        val same = others.filter { it.kind == Kind.ATTRIBUTE && it.group == decl.group }
                        same.filter { below(decl.owner!!, it.owner!!) } to same.filter { below(it.owner!!, decl.owner!!) }
                    }
                    Kind.INH_DEFINITION -> emptyList<Decl>() to others.filter { it.kind == Kind.INH_EQUATION && it.group == decl.group }
                    Kind.INH_EQUATION -> others.filter { it.kind == Kind.INH_DEFINITION && it.group == decl.group } to emptyList()
                    Kind.RULE -> {
                        val same = others.filter { it.kind == Kind.RULE && it.group == decl.group }
                        if (decl.abstract) emptyList<Decl>() to same.filter { !it.abstract }
                        else same.filter { it.abstract } to emptyList()
                    }
                }
                if (supers.isNotEmpty() || subs.isNotEmpty()) {
                    result[decl.token.startIndex] = ForgeHierarchy(decl.abstract, supers.map(::related), subs.map(::related))
                }
            }
            return result
        }
    }

    /** Walks the tree of the analyzed file while the attributes of the analysis are there. */
    private class Collector(context: Context) {
        private val symTab = SymTab.instance(context)
        private val typeMap = TypeMap.instance(context)
        val resolutions = mutableMapOf<Int, ForgeResolution>()
        val declarationTypes = mutableMapOf<Pair<Int, Int>, String>()

        fun visit(tree: ParseTree) {
            if (tree is TerminalNode) {
                val context = tree.parent as? ParserRuleContext ?: return
                val token = tree.symbol
                if (token.type != ForgeParser.IDENT || token.startIndex < 0) return
                // a type parameter: the compiler has no symbol for it, its declaration is in the `<...>` of the function
                if (context is ForgeParser.NamedTypeContext) {
                    typeParameterDeclaration(context, token)?.let { declaration ->
                        resolutions[token.startIndex] = ForgeResolution(
                            token.text, null, declaration.line, declaration.charPositionInLine, ForgeNameRole.TYPE_PARAMETER,
                        )
                        return
                    }
                }
                val symbol = resolve(context, token) ?: return
                val span = symbol.span ?: return
                if (span.startLine <= 0) return
                if (span.source == null && (symbol is VarSymbol || symbol is ConstSymbol)) {
                    declarationTypes[span.startLine to span.startCol] = symbol.type.toString()
                }
                resolutions[token.startIndex] = ForgeResolution(
                    com.leko.forge.types.displayName(symbol.name), span.source, span.startLine, span.startCol, role(symbol, nameRole(context, token)),
                )
                return
            }
            for (i in 0 until tree.childCount) visit(tree.getChild(i))
        }

        private fun type(name: String): Symbol? = symTab.table.values.firstOrNull { it.name == name && it.kind == Kind.TYP }

        private fun resolve(context: ParserRuleContext, token: Token): Symbol? {
            val symbol: Symbol? = when (context) {
                is ForgeParser.IdentifierContext -> context.symbol ?: context.port ?: context.local
                is ForgeParser.FieldAccessContext -> context.symbol.takeIf { token === context.name }
                // also a call of a variable whose value is a function
                is ForgeParser.FunctionCallContext -> (context.symbol ?: context.port
                    ?: context.callee).takeIf { token === context.name }
                is ForgeParser.MethodCallContext -> context.symbol.takeIf { token === context.name }
                is ForgeParser.TypePatternContext -> when (token) {
                    context.name -> context.symbol
                    context.receiver -> type(token.text)
                    else -> null
                }
                is ForgeParser.NamedTypeContext -> type(token.text)
                is ForgeParser.NodeDefContext -> type(token.text).takeIf { token === context.parent }
                is ForgeParser.NodeInterfaceImplContext -> type(token.text)
                is ForgeParser.SynDefContext -> type(token.text).takeIf { token === context.node }
                is ForgeParser.CircularDefContext -> type(token.text).takeIf { token === context.node }
                is ForgeParser.CollContext -> type(token.text).takeIf { token === context.node }
                is ForgeParser.GraphDefContext -> type(token.text).takeIf { token === context.rootNode }
                is ForgeParser.InhImplContext -> when (token) {
                    context.node -> type(token.text)
                    context.attrName -> context.symbol ?: symTab.inheritedAttributes[token.text]
                    else -> null
                }
                is ForgeParser.ContribContext -> when (token) {
                    context.node -> type(token.text)
                    context.collName -> context.symbol
                    else -> null
                }
                is ForgeParser.FlowDefContext -> when (token) {
                    context.node -> type(token.text)
                    context.graph -> symTab.graphs.values.firstOrNull { it.name == token.text }
                    else -> null
                }
                is ForgeParser.InhDelegateContext -> {
                    val impl = context.parent as? ForgeParser.InhImplContext
                    (impl?.node?.text?.let(::type) as? ConcreteNodeSymbol)?.fields?.firstOrNull { it.name == token.text }
                }
                is ForgeParser.ParseRuleDefContext ->
                    symTab.parsers.values.firstOrNull { it.name == token.text }.takeIf { token === context.parserName }
                is ForgeParser.NonterminalRuleContext -> {
                    val rule = generateSequence(context as ParserRuleContext?) { it.parent as? ParserRuleContext }
                        .filterIsInstance<ForgeParser.ParseRuleDefContext>().firstOrNull()
                    val parser = rule?.let { r -> symTab.parsers.values.firstOrNull { it.name == r.parserName.text } }
                    parser?.let { p ->
                        typeMap.parserRuleDefs[p].orEmpty().firstOrNull { it.name == token.text }
                            ?: typeMap.parserRuleImpls[p].orEmpty().firstOrNull { it.name == token.text }
                    }
                }
                else -> null
            }
            return if (symbol is FunctionSymbol) symbol.declaration else symbol
        }

        /** The role a use of [symbol] has; the grammar's [grammarRole], unless the symbol says more. */
        private fun role(symbol: Symbol, grammarRole: ForgeNameRole?): ForgeNameRole? = when (symbol) {
            is VarSymbol -> when (symbol.varKind) {
                VarSymbol.VarKind.PARAMETER -> ForgeNameRole.PARAMETER
                VarSymbol.VarKind.VARIABLE -> ForgeNameRole.LOCAL_VARIABLE
                VarSymbol.VarKind.NODE_FIELD, VarSymbol.VarKind.RECORD_FIELD -> ForgeNameRole.FIELD
                VarSymbol.VarKind.PARENT_INH -> ForgeNameRole.ATTRIBUTE
            }
            is AttributeSymbol -> ForgeNameRole.ATTRIBUTE
            is FunctionSymbol -> if (symbol.isProperty) ForgeNameRole.PROPERTY else grammarRole
            is ConstSymbol -> ForgeNameRole.CONSTANT
            is EnumItemSymbol -> ForgeNameRole.ENUM_CONSTANT
            is PortSymbol -> ForgeNameRole.PORT
            is FlowGraphSymbol -> ForgeNameRole.GRAPH
            is NodeSymbol, is RecordSymbol, is EnumSymbol, is ParserSymbol ->
                if (grammarRole == ForgeNameRole.IDENTIFIER) ForgeNameRole.TYPE_REFERENCE else grammarRole
            else -> grammarRole
        }
    }
}
