package com.leko.forge.enter

import ForgeParser
import com.leko.forge.analysis.moduleError
import com.leko.forge.context.Context
import com.leko.forge.model.span
import com.leko.forge.types.MODULE_SEPARATOR
import com.leko.forge.types.displayName
import com.leko.forge.util.Log
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * Modules: `mod jvm { ... }` declares the types, functions, constants, parsers, graphs and traits in it as
 * `jvm::Insn` outside of it; inside, and after `use jvm` (or `use jvm::Insn` for one of them), they can be used by
 * their names. Members (fields, attributes, methods, rules) are found through their owner, so they need no module;
 * nor do inherited attributes and ports, whose names are global.
 *
 * The first phase of the analysis: it resolves the names of declarations as they are written to their qualified
 * names, which become the names of the declarations everywhere after it (`jvm::Insn` is `jvm__Insn`), and makes the
 * declarations of modules declarations of the root, so the other phases see one program without modules.
 * A `use` at the top of a file is for that file; one in a module for the module.
 */
class Modules private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Modules = context.getServices(Modules::class) { Modules(context) }
    }

    private val log = Log.instance(context)

    /** A module: its declarations by name (their qualified names), its modules and the `use`s in it. */
    private class Module(val name: String, val parent: Module?) {
        val declarations = linkedMapOf<String, String>()
        val modules = linkedMapOf<String, Module>()
        val qualifier: String = if (parent == null || parent.parent == null && parent.name.isEmpty()) name else parent.qualifier + MODULE_SEPARATOR + name

        fun qualify(declaration: String): String = if (parent == null) declaration else qualifier + MODULE_SEPARATOR + declaration

        val path: String get() = if (parent == null) "" else displayName(qualifier)
    }

    private val root = Module("", null)

    /** The `use`s of each module (the root's by file): what they make visible. */
    private val uses = mutableMapOf<Pair<Module, String?>, MutableList<Use>>()

    private class Use(val module: Module?, val declaration: Pair<String, String>?)

    fun resolve(root: ForgeParser.RootContext) {
        if (!usesModules(root)) return
        declare(this.root, root)
        enterUses(this.root, root)
        Resolver(this.root, root.fileOf()).declarations(root)
        flattenModules(root)
    }

    /** Whether there is anything to do: modules, `use`s or qualified names. */
    private fun usesModules(root: ParserRuleContext): Boolean {
        if (root.getRuleContexts(ForgeParser.ModDefContext::class.java).isNotEmpty() ||
            root.getRuleContexts(ForgeParser.UseDeclContext::class.java).isNotEmpty()) return true
        fun qualified(t: ParseTree): Boolean {
            if (t is TerminalNode) return t.symbol.type == ForgeParser.IDENT && t.text.contains("::")
            for (i in 0 until t.childCount) if (qualified(t.getChild(i))) return true
            return false
        }
        return qualified(root)
    }

    private fun ParserRuleContext.fileOf(): String? = start?.tokenSource?.sourceName

    // ---- declarations ---------------------------------------------------------------------------

    /** The names a module declares: its declarations and modules (also those of nested modules). */
    private fun declare(module: Module, ctx: ParserRuleContext) {
        for (child in ctx.children.orEmpty()) {
            val name: Token? = when (child) {
                is ForgeParser.NodeDefContext -> child.name
                is ForgeParser.EnumContext -> child.name
                is ForgeParser.RecordContext -> child.name
                is ForgeParser.TraitDefContext -> child.name
                is ForgeParser.ConstDefContext -> child.name
                is ForgeParser.ParserDefContext -> child.name
                is ForgeParser.GraphDefContext -> child.name
                is ForgeParser.FunctionContext -> if (child.receiver == null && child.property == null) child.name else null
                is ForgeParser.ModDefContext -> {
                    val sub = module.modules.getOrPut(child.name.text) { Module(child.name.text, module) }
                    if (child.name.text.contains("::") || child.name.text.contains(MODULE_SEPARATOR)) {
                        log.diag(moduleError(child.name.span, "the name of a module is a plain name"))
                    }
                    declare(sub, child)
                    null
                }
                else -> null
            }
            if (name == null) continue
            if (name.text.contains("::")) {
                log.diag(moduleError(name.span, "'${name.text}' is declared in the module it is written in; write its name without '::'"))
                continue
            }
            if (module.parent != null && name.text.contains(MODULE_SEPARATOR)) {
                log.diag(moduleError(name.span, "names in modules may not contain '$MODULE_SEPARATOR'"))
                continue
            }
            // a function and its namesake of the standard library, or a declaration twice: Enter reports those
            module.declarations.putIfAbsent(name.text, module.qualify(name.text))
        }
    }

    /** The `use`s of each module; at the top of files, of the root module for that file. */
    private fun enterUses(module: Module, ctx: ParserRuleContext) {
        for (child in ctx.children.orEmpty()) {
            when (child) {
                is ForgeParser.UseDeclContext -> {
                    val path = child.path.text
                    val use = resolveUse(module, path)
                    if (use == null) {
                        log.diag(moduleError(child.path.span, "can not find module or declaration '$path'"))
                        continue
                    }
                    val file = if (module === root) child.fileOf() else null
                    uses.getOrPut(module to file) { mutableListOf() } += use
                }
                is ForgeParser.ModDefContext -> enterUses(module.modules.getValue(child.name.text), child)
            }
        }
    }

    private fun resolveUse(from: Module, path: String): Use? {
        val segments = path.split("::")
        val module = findModule(from, segments, null) ?: run {
            // use a::b::Decl: one declaration
            if (segments.size < 2) return null
            val owner = findModule(from, segments.dropLast(1), null) ?: return null
            val qualified = owner.declarations[segments.last()] ?: return null
            return Use(null, segments.last() to qualified)
        }
        return Use(module, null)
    }

    /** The module [segments] name, looked up from [from] outwards (and in the modules [file] uses). */
    private fun findModule(from: Module, segments: List<String>, file: String?): Module? {
        var scope: Module? = from
        var first: Module? = null
        while (scope != null && first == null) {
            // a module of the scope, a used module (`use a::b`: then `b::x`) or one of its modules
            first = scope.modules[segments[0]]
                ?: usesOf(scope, file).mapNotNull { u -> u.module?.takeIf { it.name == segments[0] } ?: u.module?.modules?.get(segments[0]) }.firstOrNull()
            scope = scope.parent
        }
        var module = first ?: return null
        for (segment in segments.drop(1)) module = module.modules[segment] ?: return null
        return module
    }

    private fun usesOf(module: Module, file: String?): List<Use> =
        uses[module to (if (module === root) file else null)].orEmpty()

    // ---- references -----------------------------------------------------------------------------

    /** Resolves the names in the declarations of [module] (in [file]), with [locals] the variables in scope. */
    private inner class Resolver(val module: Module, val file: String?) {

        /** The qualified name a name written in this module refers to, `null` for none (a builtin, an error). */
        fun lookup(name: String, at: Token): String? {
            if (name.contains("::")) {
                val segments = name.split("::")
                val owner = findModule(module, segments.dropLast(1), file)
                if (owner == null) {
                    log.diag(moduleError(at.span, "can not find module '${segments.dropLast(1).joinToString("::")}'"))
                    return null
                }
                val qualified = owner.declarations[segments.last()]
                if (qualified == null) log.diag(moduleError(at.span, "module '${owner.path}' has no '${segments.last()}'"))
                return qualified
            }
            var scope: Module? = module
            while (scope != null) {
                scope.declarations[name]?.let { return it }
                val used = usesOf(scope, file).mapNotNull { u ->
                    u.module?.declarations?.get(name) ?: u.declaration?.takeIf { it.first == name }?.second
                }.distinct()
                if (used.size > 1) {
                    log.diag(moduleError(at.span, "'$name' is ambiguous: ${used.joinToString(" and ") { displayName(it) }}; write the module"))
                    return used.first()
                }
                used.firstOrNull()?.let { return it }
                scope = scope.parent
            }
            return null
        }

        /** Renames the reference [token] to what it refers to, unless it is a local variable or type parameter. */
        fun reference(token: Token?, locals: Set<String>) {
            if (token == null) return
            val name = token.text
            if (name in locals) return
            val qualified = lookup(name, token) ?: return
            if (qualified != name) (token as CommonToken).text = qualified
        }

        /** The declaration [token] of this module gets its qualified name. */
        fun declaration(token: Token) {
            val qualified = module.declarations[token.text] ?: return
            if (qualified != token.text) (token as CommonToken).text = qualified
        }

        fun declarations(ctx: ParserRuleContext) {
            for (child in ctx.children.orEmpty()) {
                if (child !is ParserRuleContext) continue
                if (child is ForgeParser.ModDefContext) {
                    Resolver(module.modules.getValue(child.name.text), file ?: child.fileOf()).declarations(child)
                    continue
                }
                // the root holds the declarations of all files; a `use` at the top of a file is for that file
                val resolver = if (module === root && child.fileOf() != file) Resolver(module, child.fileOf()) else this
                resolver.declaration(child)
            }
        }

        private fun declaration(ctx: ParserRuleContext) {
            when (ctx) {
                is ForgeParser.NodeDefContext -> {
                    declaration(ctx.name)
                    ctx.parameter().forEach { type(it.ty, emptySet()) }
                    reference(ctx.parent, emptySet())
                    ctx.forwarding()?.let { expression(it.forward, emptySet(), emptySet()) }
                }
                is ForgeParser.NodeInterfaceImplContext -> {
                    reference(ctx.extended, emptySet())
                    reference(ctx.iface, emptySet())
                    ctx.forwarding()?.let { expression(it.forward, emptySet(), emptySet()) }
                }
                is ForgeParser.EnumContext -> {
                    declaration(ctx.name)
                    val params = ctx.typeParams().names().toSet()
                    ctx.enumItem().forEach { item -> item.parameter().forEach { type(it.ty, params) } }
                }
                is ForgeParser.RecordContext -> {
                    declaration(ctx.name)
                    val params = ctx.typeParams().names().toSet()
                    ctx.recordField().forEach { type(it.ty, params) }
                }
                is ForgeParser.TraitDefContext -> {
                    declaration(ctx.name)
                    for (member in ctx.traitMember()) {
                        member.parameter().forEach { type(it.ty, setOf("Self")) }
                        type(member.ty, setOf("Self"))
                    }
                }
                is ForgeParser.FunctionContext -> {
                    if (ctx.receiver == null && ctx.property == null) declaration(ctx.name)
                    val params = ctx.typeParams().names().toSet()
                    ctx.typeParams()?.typeParam()?.forEach { p -> p.bounds.forEach { type(it, params) } }
                    ctx.receiver?.let { type(it, params) }
                    ctx.parameter().forEach { type(it.ty, params) }
                    type(ctx.ty, params)
                    ctx.expression()?.let { expression(it, ctx.parameter().map { p -> p.name.text }.toSet(), params) }
                }
                is ForgeParser.ConstDefContext -> {
                    declaration(ctx.name)
                    ctx.ty?.let { type(it, emptySet()) }
                    expression(ctx.value, emptySet(), emptySet())
                }
                is ForgeParser.SynDefContext -> {
                    reference(ctx.node, emptySet())
                    type(ctx.attrType, emptySet())
                    ctx.syn?.let { expression(it, emptySet(), emptySet()) }
                }
                is ForgeParser.InhDefContext -> {
                    type(ctx.attrType, emptySet())
                    expression(ctx.init, emptySet(), emptySet())
                }
                is ForgeParser.InhImplContext -> {
                    reference(ctx.node, emptySet())
                    for (d in ctx.inhDelegate()) {
                        d.key?.let { expression(it, emptySet(), emptySet()) }
                        expression(d.value, emptySet(), emptySet())
                    }
                }
                is ForgeParser.CircularDefContext -> {
                    reference(ctx.node, emptySet())
                    type(ctx.attrType, emptySet())
                    expression(ctx.init, emptySet(), emptySet())
                    expression(ctx.body, emptySet(), emptySet())
                }
                is ForgeParser.CollContext -> {
                    reference(ctx.node, emptySet())
                    type(ctx.collType, emptySet())
                    expression(ctx.init, emptySet(), emptySet())
                }
                is ForgeParser.ContribContext -> {
                    reference(ctx.node, emptySet())
                    expression(ctx.receiver, emptySet(), emptySet())
                    expression(ctx.value, setOf(ctx.alias?.text ?: ctx.collName.text), emptySet())
                }
                is ForgeParser.ParserDefContext -> declaration(ctx.name)
                is ForgeParser.ParseRuleDefContext -> {
                    reference(ctx.parserName, emptySet())
                    type(ctx.returnType, emptySet())
                    for (option in ctx.parserRuleOption()) {
                        val labels = mutableSetOf<String>()
                        grammar(option.parseRule(), labels)
                        option.producer?.let { expression(it, labels, emptySet()) }
                    }
                }
                is ForgeParser.GraphDefContext -> {
                    declaration(ctx.name)
                    reference(ctx.rootNode, emptySet())
                }
                is ForgeParser.FlowDefContext -> {
                    reference(ctx.graph, emptySet())
                    reference(ctx.node, emptySet())
                    flowBlock(ctx.flowBlock(), ctx.flowBinder().mapNotNull { it.name?.text }.toSet())
                }
                else -> {}
            }
        }

        private fun grammar(t: ParseTree, labels: MutableSet<String>) {
            when (t) {
                is ForgeParser.ParserLabeledContext -> t.label?.let { labels += it.text }
                is ForgeParser.ExpressionContext -> {
                    expression(t, labels, emptySet())
                    return
                }
            }
            for (i in 0 until t.childCount) grammar(t.getChild(i), labels)
        }

        private fun flowBlock(block: ForgeParser.FlowBlockContext, outer: Set<String>) {
            val locals = outer.toMutableSet()
            for (stmt in block.flowStmt()) {
                when (stmt) {
                    is ForgeParser.FlowVertexContext -> {
                        stmt.label?.let { expression(it, locals, emptySet()) }
                        locals += stmt.name.text
                    }
                    is ForgeParser.FlowIfContext -> {
                        expression(stmt.cond, locals, emptySet())
                        flowBlock(stmt.then, locals)
                        stmt.alt?.let { flowBlock(it, locals) }
                    }
                    is ForgeParser.FlowForContext -> {
                        expression(stmt.iterable, locals, emptySet())
                        flowBlock(stmt.flowBlock(), locals + stmt.name.text)
                    }
                    else -> for (i in 0 until stmt.childCount) (stmt.getChild(i) as? ForgeParser.ExpressionContext)?.let { expression(it, locals, emptySet()) }
                }
            }
        }

        fun type(ctx: ForgeParser.TypeContext?, typeParams: Set<String>) {
            when (ctx) {
                null -> {}
                is ForgeParser.NamedTypeContext -> {
                    if (ctx.args.isEmpty() && ctx.name.text in typeParams) return
                    reference(ctx.name, emptySet())
                    ctx.args.forEach { type(it, typeParams) }
                }
                else -> for (i in 0 until ctx.childCount) (ctx.getChild(i) as? ForgeParser.TypeContext)?.let { type(it, typeParams) }
            }
        }

        /** The names in an expression, with [locals] the variables and [typeParams] the type parameters in scope. */
        fun expression(ctx: ParseTree, locals: Set<String>, typeParams: Set<String>) {
            when (ctx) {
                is ForgeParser.IdentifierContext -> return reference(ctx.IDENT().symbol, locals)
                is ForgeParser.FunctionCallContext -> {
                    reference(ctx.name, locals)
                    ctx.expression().forEach { expression(it, locals, typeParams) }
                    return
                }
                is ForgeParser.TypeContext -> return type(ctx, typeParams)
                is ForgeParser.LambdaContext -> {
                    ctx.lambdaParam().forEach { p -> p.ty?.let { type(it, typeParams) } }
                    val implicit = if (ctx.ARROW() == null) listOf("it") else emptyList()
                    expression(ctx.body, locals + ctx.lambdaParam().mapNotNull { it.name?.text } + implicit, typeParams)
                    return
                }
                is ForgeParser.WhereExprContext -> {
                    val inner = locals + ctx.binding().flatMap { b -> listOfNotNull(b.name?.text) + b.names.map { it.text } }
                    for (b in ctx.binding()) {
                        b.ty?.let { type(it, typeParams) }
                        expression(b.value, inner, typeParams)
                    }
                    expression(ctx.expr, inner, typeParams)
                    return
                }
                is ForgeParser.MatchCaseContext -> {
                    val bound = locals.toMutableSet()
                    pattern(ctx.pattern(), locals, bound, typeParams)
                    ctx.cond?.let { expression(it, bound, typeParams) }
                    expression(ctx.value, bound, typeParams)
                    return
                }
                is ForgeParser.FieldAccessContext -> return expression(ctx.receiver, locals, typeParams)
                is ForgeParser.MethodCallContext -> {
                    ctx.expression().forEach { expression(it, locals, typeParams) }
                    return
                }
            }
            for (i in 0 until ctx.childCount) expression(ctx.getChild(i), locals, typeParams)
        }

        /** A pattern: its variables are added to [bound]; the values in it are expressions of the scope [locals]. */
        private fun pattern(t: ParseTree, locals: Set<String>, bound: MutableSet<String>, typeParams: Set<String>) {
            when (t) {
                is ForgeParser.PatternVariableContext -> {
                    if (t.name.text.contains("::")) {
                        log.diag(moduleError(t.name.span, "'${t.name.text}' is no module name; a list pattern is written with spaces: ${t.name.text.replace("::", " :: ")}"))
                    }
                    bound += t.name.text
                    return
                }
                is ForgeParser.TypePatternContext -> {
                    reference(t.receiver, emptySet())
                    t.patternArg().forEach { pattern(it, locals, bound, typeParams) }
                    return
                }
                is ForgeParser.ExprPatternContext -> {
                    val name = (t.expression() as? ForgeParser.IdentifierContext)?.IDENT()?.symbol
                    if (name != null && name.text.contains("::") && name.text !in locals && findModule(module, name.text.split("::").dropLast(1), file) == null) {
                        log.diag(moduleError(name.span, "'${name.text}' is no module name; a list pattern is written with spaces: ${name.text.replace("::", " :: ")}"))
                        return
                    }
                    return expression(t, locals, typeParams)
                }
            }
            for (i in 0 until t.childCount) pattern(t.getChild(i), locals, bound, typeParams)
        }
    }

    // ---- one program without modules ------------------------------------------------------------

    /** The declarations of modules become declarations of the root (with their qualified names). */
    private fun flattenModules(root: ForgeParser.RootContext) {
        val children = root.children ?: return
        val flat = mutableListOf<ParseTree>()
        fun add(t: ParseTree) {
            if (t is ForgeParser.ModDefContext) {
                for (c in t.children.orEmpty()) if (c is ParserRuleContext) add(c)
                return
            }
            if (t is ParserRuleContext) t.parent = root
            flat += t
        }
        children.forEach { add(it) }
        children.clear()
        children.addAll(flat)
    }
}
