package com.leko.forge.llir

import ForgeParser
import ForgeParserBaseVisitor
import com.leko.forge.attr.*
import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.*
import org.antlr.v4.runtime.ParserRuleContext
import com.leko.forge.parse.isStd
import com.leko.forge.parse.interpolated
import com.leko.forge.utils.Options


/**
 * Lowers an analyzed Forge file into the [LLIRModule], which the targets generate code from.
 * Runs only on files without errors, so it can rely on every expression being typed.
 */
class LLIR private constructor(private val context: Context) {

    companion object {
        fun instance(context: Context): LLIR =
            context.getServices(LLIR::class) { LLIR(context) }
    }

    private val resolve = Resolve.instance(context)

    private val symTab = SymTab.instance(context)

    private val valueTransformer = ExpressionTransformer()

    private val matchTransformer = MatchTransformer()

    private val nodes = linkedMapOf<String, Node>()
    private val enums = linkedMapOf<String, LLIREnum>()
    private val records = linkedMapOf<String, Record>()
    private val functions = mutableMapOf<Symbol, Function>()
    private val ports = linkedMapOf<String, Port>()
    private val graphs = linkedMapOf<String, FlowGraphDecl>()

    // ---- declarations ----------------------------------------------------------------------

    private fun node(sym: NodeSymbol): Node {
        nodes[sym.name]?.let { return it }
        val node = Node(sym.name)
        nodes[sym.name] = node
        when (sym) {
            is ConcreteNodeSymbol -> sym.fields.forEach { node.fields[it.name] = it.type }
            is InterfaceNodeSymbol -> node.isInterface = true
        }
        node.isBuiltin = Builtins.isBuiltin(sym)
        for (parent in sym.parents) {
            node.parents.add(node(parent))
        }
        return node
    }

    private fun node(name: String): Node = node(resolve.resolve(name) as NodeSymbol)

    private fun record(sym: RecordSymbol): Record {
        records[sym.name]?.let { return it }
        val record = Record(sym.name, sym.typeParams)
        sym.fields.forEach { record.fields[it.name] = it.type }
        record.isBuiltin = sym.span == null || Builtins.isBuiltin(sym)
        records[sym.name] = record
        return record
    }

    private fun enum(sym: EnumSymbol): LLIREnum {
        enums[sym.name]?.let { return it }
        val enum = LLIREnum(sym.name, sym.typeParams)
        for (item in sym.items) {
            val llirItem = EnumItem(sym.name, item.name)
            item.parameters.forEach { (name, type) -> llirItem.fields[name] = type }
            enum.items += llirItem
        }
        enums[sym.name] = enum
        return enum
    }

    private fun enumItem(item: EnumItemSymbol): EnumItem =
        enum(resolve.resolve(item.enumType) as EnumSymbol).items.first { it.itemName == item.name }

    private fun function(callable: CallableSymbol): Function {
        // a use of a generic function is the function
        val sym = (callable as? FunctionSymbol)?.declaration ?: callable
        functions[sym]?.let { return it }
        val receiver = when (sym) {
            is FunctionSymbol -> sym.receiver?.type
            is ParserRuleSymbol -> sym.parser.type
            else -> null
        }
        val fn = Function(
            receiver,
            sym.name,
            // the members of the traits of its type parameters first: fun <T: Ord> receives T's compareTo
            witnessParameters(sym) + sym.params.map { Parameter(it.name, it.type) },
            sym.returnType,
            (sym as? FunctionSymbol)?.typeParams.orEmpty(),
            (sym as? FunctionSymbol)?.bounds.orEmpty().mapValues { (_, bounds) -> bounds.filter { resolve.resolve(it) !is TraitSymbol } }.filterValues { it.isNotEmpty() },
        )
        functions[sym] = fn
        return fn
    }

    /** The hidden parameters of a generic function for the members of the traits its type parameters require. */
    private fun witnessParameters(sym: CallableSymbol): List<Parameter> {
        if (sym !is FunctionSymbol) return emptyList()
        return buildList {
            for (tv in sym.typeParams) for (bound in sym.bounds[tv].orEmpty()) {
                val trait = resolve.resolve(bound) as? TraitSymbol ?: continue
                for (member in trait.members) add(Parameter(witnessName(tv, trait.name, member.name), witnessType(member, TypeVar(tv))))
            }
        }
    }

    /** The type of the witness for [member] of a trait for [self]: the receiver first, then the parameters. */
    private fun witnessType(member: TraitMember, self: Type): FunctionType {
        val bindings = mapOf(SELF to self)
        return FunctionType(listOf(self) + member.params.map { it.type.substitute(bindings) }, member.returnType.substitute(bindings))
    }

    /** The values a call passes for the witnesses that Attr attached to [ctx]. */
    private fun witnessArgs(ctx: ParserRuleContext): List<Value> {
        @Suppress("UNCHECKED_CAST")
        val witnesses = TreeAttributes.attributes[ctx]?.get("witnesses") as? List<Witness> ?: return emptyList()
        return witnesses.map { witnessValue(it) }
    }

    private fun witnessValue(w: Witness): Value {
        val type = witnessType(w.member, w.actual)
        w.typeParam?.let { return Parameter(witnessName(it, w.trait.name, w.member.name), type) }
        val sym = w.symbol ?: return Panic("no ${w.member.name} for ${w.actual}")
        val self = LocalRef("__self", w.actual)
        val params = type.parameters.drop(1).mapIndexed { i, t -> LocalRef("__p$i", t) }
        return Lambda(listOf(self) + params, invoke(sym, self, params, w.nested.map { witnessValue(it) }), type)
    }

    /**
     * A call of the method or property [sym] of [receiver] (or of the function [sym] without one): of the
     * function, of a builtin of the runtimes, or of a witness for a member of a trait.
     */
    private fun invoke(sym: FunctionSymbol, receiver: Value?, args: List<Value>, witnesses: List<Value>): Value {
        sym.witnessOf?.let { tv ->
            val trait = sym.receiver as TraitSymbol
            val member = trait.members.first { it.name == sym.name }
            val fnType = witnessType(member, TypeVar(tv))
            return CallValue(Parameter(witnessName(tv, trait.name, sym.name), fnType), listOfNotNull(receiver) + args, sym.returnType)
        }
        val builtin = sym.builtin
        return when {
            builtin != null && builtin.endsWith(".size") && sym.isProperty && receiver != null ->
                AccessRecordField(record(resolve.resolve(receiver.type.nonNull()) as RecordSymbol), receiver, sym.name, IntType)
            builtin != null -> Builtin(builtin, receiver, args, sym.returnType)
            else -> Call(function(sym), receiver, witnesses + args)
        }
    }

    private fun port(sym: PortSymbol): Port = ports.getOrPut(sym.name) { Port(sym.name, sym.inherited) }

    private fun graph(sym: FlowGraphSymbol): FlowGraphDecl = graphs.getOrPut(sym.name) { FlowGraphDecl(sym.name, node(sym.root)) }

    /** The values of the constants; a use of a constant is replaced by its value. */
    private var constants: Map<ConstSymbol, ForgeParser.ExpressionContext> = emptyMap()

    fun transform(ctx: ForgeParser.RootContext): LLIRModule {
        constants = ctx.constDef().mapNotNull { def -> def.symbol?.let { it to def.value } }.toMap()
        node(symTab[VERTEX] as NodeSymbol)
        ctx.nodeDef().forEach { node(it.symbol ?: return@forEach) }
        ctx.enum_().forEach { enum(it.symbol ?: return@forEach) }
        ctx.record().forEach { record(it.symbol ?: return@forEach) }
        ctx.graphDef().forEach { graph(it.symbol ?: return@forEach) }

        ctx.synDef().forEach { transform(it) }
        ctx.circularDef().forEach { transform(it) }
        ctx.inhImpl().forEach { transform(it) }
        ctx.coll().forEach { transform(it) }
        ctx.contrib().forEachIndexed { i, it -> transform(it, i) }
        ctx.flowDef().forEach { transform(it) }

        val userFunctions = ctx.function().mapNotNull { transform(it) }

        // what the compiled file declares, as opposed to the files it imports
        val source = ctx.start?.tokenSource?.sourceName
        fun ParserRuleContext.isMain() = start?.tokenSource?.sourceName == source
        val main = MainDeclarations()
        for (f in ctx.function()) if (f.isMain()) f.symbol?.let { main.functions += function(it) }
        for (d in ctx.synDef()) if (d.isMain()) main.attributes += d.attrName.text
        for (d in ctx.circularDef()) if (d.isMain()) main.attributes += d.attrName.text
        for (d in ctx.coll()) if (d.isMain()) main.attributes += d.collName.text
        for (d in ctx.contrib()) if (d.isMain()) main.attributes += d.collName.text
        for (d in ctx.inhDef()) if (d.isMain()) main.attributes += d.attrName.text
        for (d in ctx.inhImpl()) if (d.isMain()) main.attributes += d.attrName.text
        for (d in ctx.graphDef()) if (d.isMain()) main.graphs += d.name.text
        for (d in ctx.flowDef()) if (d.isMain()) main.graphs += d.graph.text
        for (d in ctx.parserDef()) if (d.isMain()) main.parsers += d.name.text
        for (d in ctx.parseRuleDef()) if (d.isMain()) main.parsers += d.parserName.text
        val inherited = linkedMapOf<String, InhAttribute>()
        for (inh in ctx.inhDef()) {
            val sym = inh.symbol ?: continue
            val attr = InhAttribute(sym.name, sym.type)
            attr.init = inh.init.transform()
            inherited[sym.name] = attr
        }

        val parsers = linkedMapOf<String, ParserDecl>()
        for (parser in ctx.parserDef()) {
            val sym = parser.anySymbol as? ParserSymbol ?: continue
            parsers[sym.name] = ParserDecl(sym.name, sym.binary, sym.skip, sym.keywords)
        }
        // abstract rules first: their alternatives are the rules with the same name
        for (rule in ctx.parseRuleDef().filter { it.ARROW() == null }) {
            val sym = rule.symbol ?: continue
            val decl = parsers[sym.parser.name] ?: continue
            decl.rules[sym.name] = GrammarRule(sym.name, sym.returnType, isAbstract = true)
        }
        for (rule in ctx.parseRuleDef().filter { it.ARROW() != null }) {
            val sym = rule.symbol ?: continue
            val decl = parsers[sym.parser.name] ?: continue
            val grammarRule = decl.rules.getOrPut(sym.name) { GrammarRule(sym.name, sym.returnType, isAbstract = false) }
            grammarRule.alternatives += transform(rule, sym.name)
        }

        val module = LLIRModule(
            ctx.start.tokenSource?.sourceName?.takeIf { it != "<unknown>" },
            records.values.filter { !it.isBuiltin },
            enums.values.toList(),
            nodes.values.toList(),
            userFunctions,
            inherited,
            parsers.values.toList(),
            symTab.ports.values.map { port(it) },
            graphs.values.toList(),
            main,
        )
        return withoutUnusedStd(module, ctx.function().filter { it.isStd() }.mapNotNull { f -> f.symbol?.let { function(it) } }.toSet())
    }

    /**
     * The module without the functions of the standard library that the program does not use, also without
     * `-opt`: everything else is kept as it is.
     */
    private fun withoutUnusedStd(module: LLIRModule, std: Set<Function>): LLIRModule {
        if (std.isEmpty()) return module
        val program = MainDeclarations()
        program.functions += module.functions.filter { it !in std }
        for (n in module.nodes) program.attributes += n.synthetics.keys + n.circulars.keys + n.collections.keys + n.inhEquations.keys
        program.attributes += module.inherited.keys
        program.graphs += module.graphs.map { it.name }
        program.parsers += module.parsers.map { it.name }
        val keep = Options.instance(context).get("-keep").orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            // a declaration of a module by its qualified name: jvm::ClassFile
            .map { it.replace("::", com.leko.forge.types.MODULE_SEPARATOR) }.toSet()
        return DeadCodeElimination(keep).apply(module.copy(main = program)).first.copy(main = module.main)
    }

    private val ParserRuleContext.anySymbol: Symbol? get() = TreeAttributes.attributes[this]?.get("symbol") as? Symbol

    private fun transform(ctx: ForgeParser.SynDefContext) {
        val sym = ctx.symbol ?: return
        val attr = SynAttribute(node(sym.owner), sym.name, sym.type)
        attr.body = ctx.syn?.transform()
        attr.owner.synthetics[sym.name] = attr
    }

    private fun transform(ctx: ForgeParser.CircularDefContext) {
        val sym = ctx.symbol ?: return
        val attr = CircularAttribute(node(sym.owner), sym.name, sym.type)
        attr.init = ctx.init.transform()
        attr.body = ctx.body.transform()
        attr.owner.circulars[sym.name] = attr
    }

    private fun transform(ctx: ForgeParser.InhImplContext) {
        val sym = ctx.symbol ?: return
        val node = node(ctx.node.text)
        val delegates = ctx.inhDelegate().map { delegate ->
            InhDelegate(
                delegate.child.text,
                delegate.key?.transform(),
                delegate.binder?.let { LocalRef(it.name, it.type) },
                delegate.value.transform(),
            )
        }
        val existing = node.inhEquations[sym.name]
        node.inhEquations[sym.name] = InhEquation(sym.name, (existing?.delegates ?: emptyList()) + delegates)
    }

    private fun transform(ctx: ForgeParser.CollContext) {
        val sym = ctx.symbol ?: return
        val attr = CollAttribute(node(sym.owner), sym.name, sym.type)
        attr.init = ctx.init.transform()
        attr.owner.collections[sym.name] = attr
    }

    private fun transform(ctx: ForgeParser.ContribContext, id: Int) {
        val sym = ctx.symbol ?: return
        val coll = node(sym.owner).collections[sym.name] ?: return
        coll.contributions += Contribution(
            id,
            node(ctx.node.text),
            coll,
            ctx.receiver.transform(),
            ctx.alias?.text ?: sym.name,
            ctx.value.transform(),
        )
    }

    private fun transform(ctx: ForgeParser.FunctionContext): Function? {
        val sym = ctx.symbol ?: return null
        // an `ext fun` of the standard library: each use is a Builtin, which the runtime of the target implements
        if ((sym as? FunctionSymbol)?.builtin != null) return null
        val fn = function(sym)
        fn.body = ctx.expression()?.transform()
        return fn
    }

    // ---- grammars ---------------------------------------------------------------------------

    private fun attr(ctx: ParserRuleContext, name: String): Any? = TreeAttributes.attributes[ctx]?.get(name)

    private fun transform(ctx: ForgeParser.ParseRuleDefContext, ruleName: String): GrammarAlternative {
        val spec = ctx.operatorSpec()
        val operator = when (spec?.kind?.text) {
            null -> null
            "prefix" -> OperatorKind.PREFIX
            "infixLeft" -> OperatorKind.INFIX_LEFT
            "infixRight" -> OperatorKind.INFIX_RIGHT
            else -> OperatorKind.POSTFIX
        }
        val precedence = spec?.let { (if (it.negative != null) -1 else 1) * it.precedence.text.toInt() } ?: Int.MAX_VALUE
        // a token: a rule that is just one regular expression is named by the rule in error messages
        val options = ctx.parserRuleOption()
        val token = options.size == 1 && options[0].producer == null &&
                options[0].parseRule().parserAlternative().parserSequence().singleOrNull()?.parserLabeled()?.singleOrNull()
                    ?.let { it.label == null && it.predicate == null && it.parserPostfix().op == null && it.parserPostfix().count == null && it.parserPostfix().atom.terminalRule() != null } == true
        return GrammarAlternative(operator, precedence, options.map { option ->
            @Suppress("UNCHECKED_CAST")
            val labels = (attr(option, "labels") as? List<VarSymbol>).orEmpty().map { it.name to it.type }
            GrammarOption(option.parseRule().transformGrammar(if (token) ruleName else null), option.producer?.transform(), labels)
        })
    }

    private fun ForgeParser.ParseRuleContext.transformGrammar(tokenName: String?): GExpr {
        val sequences = parserAlternative().parserSequence().map { it.transformGrammar(tokenName) }
        return sequences.singleOrNull() ?: GChoice(sequences)
    }

    private fun ForgeParser.ParserSequenceContext.transformGrammar(tokenName: String?): GExpr {
        val items = parserLabeled().map { item ->
            var expr = item.parserPostfix().transformGrammar(tokenName)
            if (item.predicate != null) expr = GPredicate(expr, negate = item.predicate.text == "!")
            GItem(item.label?.text, expr)
        }
        return GSequence(items, attr(this, "value") as? Int)
    }

    private fun ForgeParser.ParserPostfixContext.transformGrammar(tokenName: String?): GExpr {
        val expr = atom.transformGrammar(tokenName)
        return when {
            count != null -> GRepeat(expr, RepeatKind.COUNT, count.transform())
            QUESTIONMARK() != null -> GRepeat(expr, RepeatKind.OPTIONAL, null)
            STAR() != null -> GRepeat(expr, RepeatKind.MANY, null)
            PLUS() != null -> GRepeat(expr, RepeatKind.SOME, null)
            else -> expr
        }
    }

    private fun ForgeParser.ParserAtomContext.transformGrammar(tokenName: String?): GExpr {
        terminalRule()?.let {
            val text = it.REGEX_STRING().text
            val regex = text.substring(1, text.length - 1)
            val valid = try { java.util.regex.Pattern.compile(regex); true } catch (_: java.util.regex.PatternSyntaxException) { false }
            if (!valid) return GTerminal(java.util.regex.Pattern.quote(regex), regex, null)
            val literal = literalOf(regex)
            return GTerminal(regex, literal, if (literal == null) tokenName else null)
        }
        bitsRule()?.let {
            val text = it.text
            val digits = text.substring(2)
            val bits = if (it.HEX() != null) {
                digits.map { c -> c.digitToInt(16).toString(2).padStart(4, '0') }.joinToString("")
            } else digits
            return GBits(bits, if (it.HEX() != null) "0x" + digits.uppercase() else text)
        }
        builtinRule()?.let { return GPrimitive(it.name.text, it.arg?.transform()) }
        nonterminalRule()?.let {
            val role = when (attr(it, "role")) {
                "left" -> NonterminalRole.LEFT
                "operand" -> NonterminalRole.OPERAND
                else -> NonterminalRole.RULE
            }
            return GNonterminal(it.name.text, role)
        }
        return parseRule().transformGrammar(tokenName)
    }

    /** The text a regular expression matches if it matches exactly one text (`fun`, `\+`, `<=`), else `null`. */
    private fun literalOf(regex: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < regex.length) {
            val c = regex[i]
            if (c == '\\') {
                val next = regex.getOrNull(i + 1) ?: return null
                if (next.isLetterOrDigit()) return null
                sb.append(next)
                i += 2
                continue
            }
            if (c in ".^$*+?()[]{}|") return null
            sb.append(c)
            i++
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun transform(ctx: ForgeParser.FlowDefContext) {
        val sym = ctx.symbol ?: return
        val node = node(sym.node)
        val fields = (sym.node as? ConcreteNodeSymbol)?.fields ?: emptyList()
        val binders = ctx.flowBinder().zip(fields).mapNotNull { (binder, field) ->
            binder.name?.let { it.text to field.name }
        }
        graph(sym.graph).flows += FlowRule(node, binders, ctx.flowBlock().transform())
    }

    private fun ForgeParser.FlowBlockContext.transform(): List<FlowStmt> = flowStmt().map { it.transform() }

    private fun ForgeParser.FlowStmtContext.transform(): FlowStmt = when (this) {
        is ForgeParser.FlowConnectContext -> FlowConnect(source.transform(), target.transform(), label?.transform())
        is ForgeParser.FlowVertexContext -> FlowVertex(name.text, label?.transform())
        is ForgeParser.FlowIfContext -> FlowIf(cond.transform(), then.transform(), alt?.transform() ?: emptyList())
        is ForgeParser.FlowForContext -> FlowFor(name.text, iterable.transform(), flowBlock().transform())
        is ForgeParser.FlowCallContext -> {
            val call = expression() as ForgeParser.FunctionCallContext
            val arg = call.expression().first().transform()
            when (call.flowBuiltin) {
                "entry" -> FlowEntry(arg)
                "sequence" -> FlowSequence(arg)
                else -> throw AssertionError(call.flowBuiltin)
            }
        }
        else -> throw AssertionError(this::class)
    }


    private fun ForgeParser.ExpressionContext.transform(): Value {
        return this.accept(valueTransformer)
    }

    private fun ParserRuleContext.transformPattern(): MatchPattern {
        return this.accept(matchTransformer)
    }

    private inner class ExpressionTransformer : ForgeParserBaseVisitor<Value>() {

        override fun visitIntValue(ctx: ForgeParser.IntValueContext): Value {
            if (ctx.type == LongType) return LongValue(ctx.value.text.toLong())
            return IntValue(ctx.value.text.toInt())
        }

        override fun visitLongValue(ctx: ForgeParser.LongValueContext): Value {
            return LongValue(ctx.value.text.dropLast(1).toLong())
        }

        override fun visitFloatValue(ctx: ForgeParser.FloatValueContext): Value {
            return FloatValue(ctx.value.text.toFloat())
        }

        override fun visitBoolValue(ctx: ForgeParser.BoolValueContext): Value {
            return BoolValue(ctx.TRUE() != null)
        }

        override fun visitStringValue(ctx: ForgeParser.StringValueContext): Value {
            val parts = ctx.interpolated ?: run {
                val text = ctx.STRING().text
                return StringValue(unescape(text.substring(1, text.length - 1)))
            }
            // "a ${x} b" is "a " + x.str() + " b"
            val values = parts.mapNotNull { part ->
                when (part) {
                    is String -> unescape(part).takeIf { it.isNotEmpty() }?.let { StringValue(it) }
                    is ForgeParser.ExpressionContext -> {
                        val value = part.transform()
                        val str = TreeAttributes.attributes[part]?.get("str") as? FunctionSymbol
                        if (str == null) value else invoke(str, value, emptyList(), emptyList())
                    }
                    else -> null
                }
            }
            if (values.isEmpty()) return StringValue("")
            // a single expression still is a str: "${x}" is "" + x.str()
            val first = if (values.size == 1 && values[0] !is StringValue) ConcatStr(StringValue(""), values[0]) else values[0]
            return values.drop(1).fold(first) { acc, v -> ConcatStr(acc, v) }
        }

        override fun visitNullValue(ctx: ForgeParser.NullValueContext?): Value {
            return NullValue
        }

        override fun visitThisExpr(ctx: ForgeParser.ThisExprContext): Value {
            return This(ctx.type)
        }

        override fun visitEnclosed(ctx: ForgeParser.EnclosedContext): Value {
            return ctx.expression().transform()
        }

        /** A variable as a value: a parameter of the function, the value an inh equation receives, or a local. */
        private fun variable(sym: VarSymbol): Value = when (sym.varKind) {
            VarSymbol.VarKind.PARAMETER -> Parameter(sym.name, sym.type)
            VarSymbol.VarKind.PARENT_INH -> AccessParentInhAttr(sym.name, sym.type)
            else -> LocalRef(sym.name, sym.type)
        }

        override fun visitListLiteral(ctx: ForgeParser.ListLiteralContext): Value {
            return ListLiteral(ctx.expression().map { it.transform() }, ctx.type)
        }

        override fun visitMapLiteral(ctx: ForgeParser.MapLiteralContext): Value {
            val entries = ctx.expression().chunked(2).map { (k, v) -> k.transform() to v.transform() }
            return MapLiteral(entries, ctx.type)
        }

        override fun visitEmptyMapLiteral(ctx: ForgeParser.EmptyMapLiteralContext): Value = MapLiteral(emptyList(), ctx.type)

        override fun visitSetLiteral(ctx: ForgeParser.SetLiteralContext): Value =
            Builtin("Set.of", null, ctx.expression().map { it.transform() }, ctx.type)

        override fun visitRangeLiteral(ctx: ForgeParser.RangeLiteralContext): Value =
            Builtin("range", null, listOf(ctx.lo.transform(), ctx.hi.transform()), ctx.type)

        /** An operator applied to a value of a type that defines it with `op fun`: a call of that function. */
        private fun ParserRuleContext.operatorCall(receiver: ForgeParser.ExpressionContext, vararg args: Value): Value? {
            val fn = operator ?: return null
            return invoke(fn, receiver.transform(), args.toList(), witnessArgs(this))
        }

        override fun visitShift(ctx: ForgeParser.ShiftContext): Value {
            ctx.operatorCall(ctx.left, ctx.right.transform())?.let { return it }
            val l = ctx.left.transform()
            val r = ctx.right.transform()
            return when {
                ctx.LT().isNotEmpty() -> ShlInt(l, r)
                ctx.GT().size == 3 -> UshrInt(l, r)
                else -> ShrInt(l, r)
            }
        }

        override fun visitSliceAccess(ctx: ForgeParser.SliceAccessContext): Value {
            val lo = ctx.lo?.transform() ?: NullValue
            val hi = ctx.hi?.transform() ?: NullValue
            ctx.operatorCall(ctx.container, lo, hi)?.let { return it }
            val name = if (ctx.container.type == StrType) "str.slice" else "List.slice"
            return Builtin(name, ctx.container.transform(), listOf(lo, hi), ctx.type)
        }

        override fun visitNegation(ctx: ForgeParser.NegationContext): Value {
            ctx.operatorCall(ctx.expression())?.let { return it }
            return when (ctx.type) {
                IntType, LongType -> NegateInt(ctx.expression().transform())
                FloatType -> NegateFloat(ctx.expression().transform())
                else -> throw AssertionError()
            }
        }

        override fun visitInversion(ctx: ForgeParser.InversionContext): Value {
            ctx.operatorCall(ctx.expression())?.let { return it }
            return when (ctx.type) {
                BoolType -> InvertBool(ctx.expression().transform())
                IntType, LongType -> InvertInt(ctx.expression().transform())
                else -> throw AssertionError()
            }
        }

        /** Converts int operands to float when the other one is a float. */
        private fun numeric(
            ctx: ForgeParser.ExpressionContext,
            left: ForgeParser.ExpressionContext,
            right: ForgeParser.ExpressionContext,
            ints: (Value, Value) -> Value,
            floats: (Value, Value) -> Value,
        ): Value {
            val l = left.transform()
            val r = right.transform()
            val lt = left.type
            val rt = right.type
            return when {
                lt == FloatType || rt == FloatType -> floats(convert(l, lt, FloatType), convert(r, rt, FloatType))
                lt == LongType || rt == LongType -> ints(convert(l, lt, LongType), convert(r, rt, LongType))
                lt == IntType && rt == IntType -> ints(l, r)
                else -> throw AssertionError("${left.type} and ${right.type} in ${ctx.text}")
            }
        }

        override fun visitMulDivMod(ctx: ForgeParser.MulDivModContext): Value = when {
            ctx.operator != null -> ctx.operatorCall(ctx.left, ctx.right.transform())!!
            ctx.STAR() != null -> numeric(ctx, ctx.left, ctx.right, ::MulInt, ::MulFloat)
            ctx.SLASH() != null -> numeric(ctx, ctx.left, ctx.right, ::DivInt, ::DivFloat)
            else -> integers(ctx.left, ctx.right, ::ModInt)
        }

        /** An operation on ints or longs; an int operand of a long one is converted. */
        private fun integers(left: ForgeParser.ExpressionContext, right: ForgeParser.ExpressionContext, op: (Value, Value) -> Value): Value {
            val long = left.type == LongType || right.type == LongType
            val to = if (long) LongType else IntType
            return op(convert(left.transform(), left.type, to), convert(right.transform(), right.type, to))
        }

        override fun visitAddSub(ctx: ForgeParser.AddSubContext): Value {
            ctx.operatorCall(ctx.left, ctx.right.transform())?.let { return it }
            if (ctx.PLUS() != null && ctx.left.type == StrType) {
                return ConcatStr(ctx.left.transform(), ctx.right.transform())
            }
            return if (ctx.PLUS() != null) numeric(ctx, ctx.left, ctx.right, ::AddInt, ::AddFloat)
            else numeric(ctx, ctx.left, ctx.right, ::SubInt, ::SubFloat)
        }

        private fun boolOrInt(
            ctx: ForgeParser.ExpressionContext,
            left: ForgeParser.ExpressionContext,
            right: ForgeParser.ExpressionContext,
            ints: (Value, Value) -> Value,
            bools: (Value, Value) -> Value,
        ): Value = when (left.type) {
            IntType, LongType -> integers(left, right, ints)
            BoolType -> bools(left.transform(), right.transform())
            else -> throw AssertionError(ctx.text)
        }

        override fun visitLogicalAnd(ctx: ForgeParser.LogicalAndContext): Value =
            boolOrInt(ctx, ctx.left, ctx.right, ::AndInt, ::AndBool)

        override fun visitLogicalOr(ctx: ForgeParser.LogicalOrContext): Value =
            boolOrInt(ctx, ctx.left, ctx.right, ::OrInt, ::OrBool)

        override fun visitLogicalXor(ctx: ForgeParser.LogicalXorContext): Value =
            boolOrInt(ctx, ctx.left, ctx.right, ::XorInt, ::XorBool)

        override fun visitIand(ctx: ForgeParser.IandContext): Value = integers(ctx.left, ctx.right, ::IAnd)

        override fun visitIor(ctx: ForgeParser.IorContext): Value = integers(ctx.left, ctx.right, ::IOr)

        override fun visitRelational(ctx: ForgeParser.RelationalContext): Value = when {
            // a.compareTo(b) < 0, ...
            ctx.operator != null -> {
                val c = ctx.operatorCall(ctx.left, ctx.right.transform())!!
                when {
                    ctx.LT() != null -> LtInt(c, IntValue(0))
                    ctx.GT() != null -> GtInt(c, IntValue(0))
                    ctx.LEQ() != null -> LeqInt(c, IntValue(0))
                    else -> GeqInt(c, IntValue(0))
                }
            }
            ctx.LT() != null -> numeric(ctx, ctx.left, ctx.right, ::LtInt, ::LtFloat)
            ctx.GT() != null -> numeric(ctx, ctx.left, ctx.right, ::GtInt, ::GtFloat)
            ctx.LEQ() != null -> numeric(ctx, ctx.left, ctx.right, ::LeqInt, ::LeqFloat)
            else -> numeric(ctx, ctx.left, ctx.right, ::GeqInt, ::GeqFloat)
        }

        override fun visitConcat(ctx: ForgeParser.ConcatContext): Value {
            ctx.operatorCall(ctx.left, ctx.right.transform())?.let { return it }
            return when (ctx.left.type) {
                is MapType -> MergeMaps(ctx.left.transform(), ctx.right.transform())
                is SetType -> Builtin("Set.union", ctx.left.transform(), listOf(ctx.right.transform()), ctx.type)
                else -> MergeLists(ctx.left.transform(), ctx.right.transform())
            }
        }

        override fun visitIfElse(ctx: ForgeParser.IfElseContext): Value {
            return IfElse(
                ctx.cond.transform(),
                ctx.then.transform(),
                ctx.alt.transform(),
                ctx.type
            )
        }

        override fun visitEquality(ctx: ForgeParser.EqualityContext): Value {
            val lhsTy = ctx.left.type
            val rhsTy = ctx.right.type
            val eq = when {
                lhsTy is NullType && rhsTy is NullType -> BoolValue(true)
                lhsTy is NullType -> IsNull(ctx.right.transform())
                rhsTy is NullType -> IsNull(ctx.left.transform())
                else -> {
                    val l = ctx.left.transform()
                    val r = ctx.right.transform()
                    val lt = if (lhsTy is OptionalType) lhsTy.elem else lhsTy
                    val rt = if (rhsTy is OptionalType) rhsTy.elem else rhsTy
                    val numeric = setOf(IntType, LongType, FloatType)
                    when {
                        lt == IntType && rt == IntType -> IntEquals(l, r)
                        lt in numeric && rt in numeric && (lt == FloatType || rt == FloatType) ->
                            FloatEquals(convert(l, lt, FloatType), convert(r, rt, FloatType))
                        lt in numeric && rt in numeric -> IntEquals(convert(l, lt, LongType), convert(r, rt, LongType))
                        lt == BoolType -> BoolEquals(l, r)
                        lt == StrType -> StringEquals(l, r)
                        lt is ListType -> ListEquals(l, r)
                        lt is MapType -> MapEquals(l, r)
                        lt is SetType -> SetEquals(l, r)
                        else -> {
                            val lhsSym = resolve.resolve(lt)
                            val rhsSym = resolve.resolve(rt)
                            when {
                                lhsSym is EnumSymbol -> EnumEquals(l, r)
                                lhsSym is NodeSymbol && rhsSym is NodeSymbol ->
                                    NodeEquals(l, lhsSym is ConcreteNodeSymbol, r, rhsSym is ConcreteNodeSymbol)
                                else -> RecordEquals(l, r)
                            }
                        }
                    }
                }
            }
            return if (ctx.NOTEQUALS() != null) InvertBool(eq) else eq
        }

        override fun visitInOp(ctx: ForgeParser.InOpContext): Value {
            val op = ctx.operatorCall(ctx.right, ctx.left.transform()) ?: when (ctx.right.type) {
                is MapType -> InMapKeys(ctx.right.transform(), ctx.left.transform())
                is SetType -> Builtin("Set.contains", ctx.right.transform(), listOf(ctx.left.transform()), BoolType)
                StrType -> Builtin("str.contains", ctx.right.transform(), listOf(ctx.left.transform()), BoolType)
                else -> InList(ctx.right.transform(), ctx.left.transform())
            }
            return if (ctx.NOTIN() != null) InvertBool(op) else op
        }

        override fun visitElvis(ctx: ForgeParser.ElvisContext): Value {
            return Elvis(ctx.expr.transform(), ctx.alt.transform(), ctx.type)
        }

        override fun visitNullCheck(ctx: ForgeParser.NullCheckContext): Value {
            return NotNull(ctx.value.transform(), ctx.type)
        }

        override fun visitIdentifier(ctx: ForgeParser.IdentifierContext): Value {
            ctx.port?.let { return PortValue(port(it)) }
            return when (val sym = ctx.symbol) {
                // a function as a value: { a, b -> f(a, b) }
                is FunctionSymbol -> {
                    val type = sym.type as FunctionType
                    val params = type.parameters.mapIndexed { i, t -> LocalRef("__a$i", t) }
                    Lambda(params, invoke(sym, null, params, witnessArgs(ctx)), type)
                }
                is VarSymbol -> variable(sym)
                is FlowGraphSymbol -> FlowGraphRef(graph(sym))
                // inlined: the generated code has no variable for the constant
                is ConstSymbol -> constants.getValue(sym).transform()
                else -> throw AssertionError("identifier ${ctx.text} resolves to $sym")
            }
        }

        override fun visitFieldAccess(ctx: ForgeParser.FieldAccessContext): Value {
            val sym = ctx.symbol
            if (sym is FunctionSymbol) return invoke(sym, ctx.receiver.transform(), emptyList(), witnessArgs(ctx))
            if (sym is EnumItemSymbol) return CreateEnum(enumItem(sym), emptyList(), ctx.type)
            return access(sym!!, ctx.receiver, ctx.name.text, ctx.receiver.type)
        }

        /** The member [sym] (a field, an attribute, a property) of the value of [receiver]. */
        fun access(sym: Symbol, receiver: ForgeParser.ExpressionContext, name: String, recvTy: Type): Value {
            return when (sym) {
                is VarSymbol -> when (sym.varKind) {
                    VarSymbol.VarKind.NODE_FIELD -> {
                        val owner = resolve.resolve(recvTy.nonNull()) as NodeSymbol
                        AccessNodeField(node(owner), receiver.transform(), name, sym.type)
                    }
                    VarSymbol.VarKind.PARENT_INH -> AccessParentInhAttr(sym.name, sym.type)
                    else -> {
                        val owner = resolve.resolve(recvTy.nonNull()) as RecordSymbol
                        AccessRecordField(record(owner), receiver.transform(), name, sym.type)
                    }
                }
                is AttributeSymbol -> AccessNodeAttr(receiver.transform(), sym.name, sym.type)
                // a property: `size` of the builtin types as it always was, others are methods without parameters
                is FunctionSymbol -> invoke(sym, receiver.transform(), emptyList(), emptyList())
                else -> throw AssertionError("field $name resolves to $sym")
            }
        }

        override fun visitContainerAccess(ctx: ForgeParser.ContainerAccessContext): Value {
            ctx.operatorCall(ctx.container, ctx.key.transform())?.let { return it }
            return when (ctx.container.type) {
                StrType -> Builtin("str.get", ctx.container.transform(), listOf(ctx.key.transform()), StrType)
                is MapType -> AccessMapContainer(ctx.container.transform(), ctx.key.transform(), ctx.type)
                else -> AccessListContainer(ctx.container.transform(), ctx.key.transform(), ctx.type)
            }
        }

        override fun visitListComprehension(ctx: ForgeParser.ListComprehensionContext): Value {
            return if (ctx.WITH() == null) {
                MapOp(ctx.iterable.transform(), ctx.name.text, ctx.put.transform())
            }
            else {
                FoldOp(ctx.iterable.transform(), ctx.accInit.transform(), ctx.name.text, ctx.acc.text, ctx.put.transform(), ctx.type)
            }
        }

        override fun visitCast(ctx: ForgeParser.CastContext): Value {
            val value = ctx.value.transform()
            val from = ctx.value.type
            val to = ctx.type
            val numeric = setOf(IntType, LongType, FloatType)
            return if (from in numeric && to in numeric) convert(value, from, to) else if (from == to) value else Cast(value, to)
        }

        override fun visitInstanceOf(ctx: ForgeParser.InstanceOfContext): Value {
            return IsInstanceOf(ctx.value.transform(), ctx.ty.toType())
        }

        override fun visitFunctionCall(ctx: ForgeParser.FunctionCallContext): Value {
            ctx.port?.let { return PortRefValue(port(it), ctx.expression().first().transform()) }
            if (ctx.flowBuiltin == "bound") return BoundCheck(ctx.expression().first().transform())

            val args = ctx.expression().map { it.transform() }
            // a variable whose value is a function
            when (val callee = TreeAttributes.attributes[ctx]?.get("callee")) {
                is VarSymbol -> return CallValue(variable(callee), args, ctx.type)
                is ConstSymbol -> return CallValue(constants.getValue(callee).transform(), args, ctx.type)
            }
            val sym = ctx.symbol
            return when (sym) {
                is ConcreteNodeSymbol -> CreateNode(node(sym), args)
                is RecordSymbol -> CreateRecord(record(sym), args, ctx.type)
                is FunctionSymbol -> invoke(sym, null, args, witnessArgs(ctx))
                else -> throw AssertionError("call ${ctx.text} resolves to $sym")
            }
        }

        override fun visitMethodCall(ctx: ForgeParser.MethodCallContext): Value {
            val args = ctx.expression().drop(1).map { it.transform() }
            // a field or property whose value is a function
            (TreeAttributes.attributes[ctx]?.get("field") as? Symbol)?.let { field ->
                return CallValue(access(field, ctx.receiver, ctx.name.text, ctx.receiver.type), args, ctx.type)
            }
            return when (val sym = ctx.symbol) {
                is EnumItemSymbol -> CreateEnum(enumItem(sym), args, ctx.type)
                is FunctionSymbol -> invoke(sym, ctx.receiver.transform(), args, witnessArgs(ctx))
                is ParserRuleSymbol -> Call(function(sym), null, args)
                else -> throw AssertionError("method ${ctx.text} resolves to $sym")
            }
        }

        override fun visitMatch(ctx: ForgeParser.MatchContext): Value {
            return Match(
                ctx.switch_.transform(),
                ctx.matchCase().map {
                    MatchCase(it.pattern().transformPattern(), it.cond?.transform(), it.value.transform())
                },
                ctx.alt?.transform(),
                ctx.type
            )
        }

        /** `body where a = 1, b = a + 1`: nested Lets, in the order of the dependencies of the values. */
        override fun visitWhereExpr(ctx: ForgeParser.WhereExprContext): Value {
            @Suppress("UNCHECKED_CAST")
            val order = TreeAttributes.attributes[ctx]?.get("order") as List<ForgeParser.BindingContext>
            var result = ctx.expr.transform()
            for (b in order.reversed()) {
                @Suppress("UNCHECKED_CAST")
                val symbols = TreeAttributes.attributes[b]?.get("symbols") as List<VarSymbol>
                val value = b.value.transform()
                result = if (b.name != null) {
                    Let(symbols.single().name, value, result, symbols.single().type)
                } else {
                    // (a, b) = pair: the tuple once, then its elements
                    val tuple = "__t" + (destructured++)
                    // the type Attr gave it: that of a call of a generic function is instantiated, the call's is not
                    val tupleRef = LocalRef(tuple, b.value.type)
                    Let(tuple, value, symbols.withIndex().reversed().fold(result) { acc, (i, sym) ->
                        Let(sym.name, TupleGet(tupleRef, i, sym.type), acc, sym.type)
                    }, b.value.type)
                }
            }
            return result
        }

        private var destructured = 0

        override fun visitLambda(ctx: ForgeParser.LambdaContext): Value {
            @Suppress("UNCHECKED_CAST")
            val params = (TreeAttributes.attributes[ctx]?.get("params") as List<VarSymbol>).map { LocalRef(it.name, it.type) }
            return Lambda(params, ctx.body.transform(), ctx.type as FunctionType)
        }

        override fun visitValueCall(ctx: ForgeParser.ValueCallContext): Value =
            CallValue(ctx.callee.transform(), ctx.expression().drop(1).map { it.transform() }, ctx.type)

        override fun visitTupleLiteral(ctx: ForgeParser.TupleLiteralContext): Value =
            TupleLiteral(ctx.expression().map { it.transform() }, ctx.type as TupleType)

        override fun visitTupleAccess(ctx: ForgeParser.TupleAccessContext): Value {
            @Suppress("UNCHECKED_CAST")
            val path = TreeAttributes.attributes[ctx]?.get("path") as List<Int>
            var value = ctx.receiver.transform()
            for (index in path) value = TupleGet(value, index, (value.type as TupleType).elems[index])
            return value
        }
    }

    private inner class MatchTransformer : ForgeParserBaseVisitor<MatchPattern>() {

        override fun visitPattern(ctx: ForgeParser.PatternContext): MatchPattern {
            return (ctx.getChild(0) as ParserRuleContext).transformPattern()
        }

        override fun visitWildcardPattern(ctx: ForgeParser.WildcardPatternContext): MatchPattern {
            return WildcardPattern
        }

        override fun visitPatternVariable(ctx: ForgeParser.PatternVariableContext): MatchPattern {
            val sym = ctx.anySymbol as VarSymbol
            return BindPattern(LocalRef(sym.name, sym.type))
        }

        override fun visitListPattern(ctx: ForgeParser.ListPatternContext): MatchPattern {
            return ListPattern(ctx.patternArg().map { it.transformPattern() })
        }

        override fun visitSplitPattern(ctx: ForgeParser.SplitPatternContext): MatchPattern {
            return SplitPattern(ctx.element.transformPattern(), ctx.rest.transformPattern())
        }

        override fun visitSplitPatternArg(ctx: ForgeParser.SplitPatternArgContext): MatchPattern {
            return SplitPattern(ctx.element.transformPattern(), ctx.rest.transformPattern())
        }

        override fun visitExprPattern(ctx: ForgeParser.ExprPatternContext): MatchPattern {
            return ValuePattern(ctx.expression().transform())
        }

        override fun visitTypePattern(ctx: ForgeParser.TypePatternContext): MatchPattern {
            val item = enumItem(ctx.anySymbol as EnumItemSymbol)
            @Suppress("UNCHECKED_CAST")
            val fieldTypes = TreeAttributes.attributes[ctx]?.get("fieldTypes") as? List<Type> ?: item.fields.values.toList()
            return ConstructorPattern(item, ctx.patternArg().map { it.transformPattern() }, fieldTypes)
        }

        override fun visitTuplePattern(ctx: ForgeParser.TuplePatternContext): MatchPattern {
            val types = (ctx.type as? TupleType)?.elems ?: ctx.patternArg().map { ArbitraryType }
            return TuplePattern(ctx.patternArg().map { it.transformPattern() }, types)
        }

        override fun visitChildren(node: org.antlr.v4.runtime.tree.RuleNode): MatchPattern {
            // labeled pattern arguments (`#typePatternArg`, ...) wrap exactly one pattern
            return (node.getChild(0) as ParserRuleContext).transformPattern()
        }
    }

    private fun Type.nonNull(): Type = if (this is OptionalType) elem else this

    /** [value] of the numeric type [from] as one of [to]. */
    private fun convert(value: Value, from: Type, to: Type): Value = when {
        from == to -> value
        value is IntValue && to == LongType -> LongValue(value.value.toLong())
        from == IntType && to == LongType -> Int2Long(value)
        from == IntType && to == FloatType -> Int2Float(value)
        from == LongType && to == IntType -> Long2Int(value)
        from == LongType && to == FloatType -> Long2Float(value)
        from == FloatType && to == IntType -> Float2Int(value)
        from == FloatType && to == LongType -> Float2Long(value)
        else -> value
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                sb.append(
                    when (val e = s[i]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        else -> e
                    }
                )
            }
            else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
