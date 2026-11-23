package com.leko.forge.llir

import ForgeParser
import ForgeParserBaseVisitor
import com.leko.forge.attr.*
import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.*
import org.antlr.v4.runtime.ParserRuleContext


/**
 * Lowers an analyzed Forge file into the [LLIRModule], which the targets generate code from.
 * Runs only on files without errors, so it can rely on every expression being typed.
 */
class LLIR private constructor(context: Context) {

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
        val record = Record(sym.name)
        sym.fields.forEach { record.fields[it.name] = it.type }
        record.isBuiltin = sym.span == null || Builtins.isBuiltin(sym)
        records[sym.name] = record
        return record
    }

    private fun enum(sym: EnumSymbol): LLIREnum {
        enums[sym.name]?.let { return it }
        val enum = LLIREnum(sym.name)
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

    private fun function(sym: CallableSymbol): Function {
        functions[sym]?.let { return it }
        val receiver = when (sym) {
            is FunctionSymbol -> sym.receiver?.type
            is ParserRuleSymbol -> sym.parser.type
            else -> null
        }
        val fn = Function(
            receiver,
            sym.name,
            sym.params.map { Parameter(it.name, it.type) },
            sym.returnType,
        )
        functions[sym] = fn
        return fn
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

        return LLIRModule(
            ctx.start.tokenSource?.sourceName?.takeIf { it != "<unknown>" },
            records.values.filter { !it.isBuiltin },
            enums.values.toList(),
            nodes.values.toList(),
            userFunctions,
            inherited,
            parsers.values.toList(),
            symTab.ports.values.map { port(it) },
            graphs.values.toList(),
        )
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
            return IntValue(ctx.value.text.toInt())
        }

        override fun visitFloatValue(ctx: ForgeParser.FloatValueContext): Value {
            return FloatValue(ctx.value.text.toFloat())
        }

        override fun visitBoolValue(ctx: ForgeParser.BoolValueContext): Value {
            return BoolValue(ctx.TRUE() != null)
        }

        override fun visitStringValue(ctx: ForgeParser.StringValueContext): Value {
            val text = ctx.STRING().text
            return StringValue(unescape(text.substring(1, text.length - 1)))
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

        override fun visitListLiteral(ctx: ForgeParser.ListLiteralContext): Value {
            return ListLiteral(ctx.expression().map { it.transform() }, ctx.type)
        }

        override fun visitMapLiteral(ctx: ForgeParser.MapLiteralContext): Value {
            val entries = ctx.expression().chunked(2).map { (k, v) -> k.transform() to v.transform() }
            return MapLiteral(entries, ctx.type)
        }

        override fun visitNegation(ctx: ForgeParser.NegationContext): Value {
            return when (ctx.type) {
                IntType -> NegateInt(ctx.expression().transform())
                FloatType -> NegateFloat(ctx.expression().transform())
                else -> throw AssertionError()
            }
        }

        override fun visitInversion(ctx: ForgeParser.InversionContext): Value {
            return when (ctx.type) {
                BoolType -> InvertBool(ctx.expression().transform())
                IntType -> InvertInt(ctx.expression().transform())
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
            return when (left.type to right.type) {
                IntType to IntType -> ints(l, r)
                FloatType to IntType -> floats(l, Int2Float(r))
                IntType to FloatType -> floats(Int2Float(l), r)
                FloatType to FloatType -> floats(l, r)
                else -> throw AssertionError("${left.type} and ${right.type} in ${ctx.text}")
            }
        }

        override fun visitMulDivMod(ctx: ForgeParser.MulDivModContext): Value = when {
            ctx.STAR() != null -> numeric(ctx, ctx.left, ctx.right, ::MulInt, ::MulFloat)
            ctx.SLASH() != null -> numeric(ctx, ctx.left, ctx.right, ::DivInt, ::DivFloat)
            else -> ModInt(ctx.left.transform(), ctx.right.transform())
        }

        override fun visitAddSub(ctx: ForgeParser.AddSubContext): Value {
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
            IntType -> ints(left.transform(), right.transform())
            BoolType -> bools(left.transform(), right.transform())
            else -> throw AssertionError(ctx.text)
        }

        override fun visitLogicalAnd(ctx: ForgeParser.LogicalAndContext): Value =
            boolOrInt(ctx, ctx.left, ctx.right, ::AndInt, ::AndBool)

        override fun visitLogicalOr(ctx: ForgeParser.LogicalOrContext): Value =
            boolOrInt(ctx, ctx.left, ctx.right, ::OrInt, ::OrBool)

        override fun visitLogicalXor(ctx: ForgeParser.LogicalXorContext): Value =
            boolOrInt(ctx, ctx.left, ctx.right, ::XorInt, ::XorBool)

        override fun visitIand(ctx: ForgeParser.IandContext): Value {
            return IAnd(ctx.left.transform(), ctx.right.transform())
        }

        override fun visitIor(ctx: ForgeParser.IorContext): Value {
            return IOr(ctx.left.transform(), ctx.right.transform())
        }

        override fun visitRelational(ctx: ForgeParser.RelationalContext): Value = when {
            ctx.LT() != null -> numeric(ctx, ctx.left, ctx.right, ::LtInt, ::LtFloat)
            ctx.GT() != null -> numeric(ctx, ctx.left, ctx.right, ::GtInt, ::GtFloat)
            ctx.LEQ() != null -> numeric(ctx, ctx.left, ctx.right, ::LeqInt, ::LeqFloat)
            else -> numeric(ctx, ctx.left, ctx.right, ::GeqInt, ::GeqFloat)
        }

        override fun visitConcat(ctx: ForgeParser.ConcatContext): Value {
            val lhsTy = ctx.left.type
            return if (lhsTy is MapType) MergeMaps(ctx.left.transform(), ctx.right.transform())
            else MergeLists(ctx.left.transform(), ctx.right.transform())
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
                    when {
                        lt == IntType && rt == IntType -> IntEquals(l, r)
                        lt == FloatType && rt == IntType -> FloatEquals(l, Int2Float(r))
                        lt == IntType && rt == FloatType -> FloatEquals(Int2Float(l), r)
                        lt == FloatType && rt == FloatType -> FloatEquals(l, r)
                        lt == BoolType -> BoolEquals(l, r)
                        lt == StrType -> StringEquals(l, r)
                        lt is ListType -> ListEquals(l, r)
                        lt is MapType -> MapEquals(l, r)
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
            val op = if (ctx.right.type is MapType) {
                InMapKeys(ctx.right.transform(), ctx.left.transform())
            }
            else {
                InList(ctx.right.transform(), ctx.left.transform())
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
                is VarSymbol -> when (sym.varKind) {
                    VarSymbol.VarKind.PARAMETER -> Parameter(sym.name, sym.type)
                    VarSymbol.VarKind.PARENT_INH -> AccessParentInhAttr(sym.name, sym.type)
                    else -> LocalRef(sym.name, sym.type)
                }
                is FlowGraphSymbol -> FlowGraphRef(graph(sym))
                // inlined: the generated code has no variable for the constant
                is ConstSymbol -> constants.getValue(sym).transform()
                else -> throw AssertionError("identifier ${ctx.text} resolves to $sym")
            }
        }

        override fun visitFieldAccess(ctx: ForgeParser.FieldAccessContext): Value {
            val sym = ctx.symbol
            val recvTy = ctx.receiver.type
            val name = ctx.name.text
            return when (sym) {
                is VarSymbol -> when (sym.varKind) {
                    VarSymbol.VarKind.NODE_FIELD -> {
                        val owner = resolve.resolve(recvTy.nonNull()) as NodeSymbol
                        AccessNodeField(node(owner), ctx.receiver.transform(), name, sym.type)
                    }
                    VarSymbol.VarKind.PARENT_INH -> AccessParentInhAttr(sym.name, sym.type)
                    else -> {
                        val owner = resolve.resolve(recvTy.nonNull()) as RecordSymbol
                        AccessRecordField(record(owner), ctx.receiver.transform(), name, sym.type)
                    }
                }
                is EnumItemSymbol -> CreateEnum(enumItem(sym), emptyList())
                is AttributeSymbol -> AccessNodeAttr(ctx.receiver.transform(), sym.name, sym.type)
                else -> throw AssertionError("field ${ctx.text} resolves to $sym")
            }
        }

        override fun visitContainerAccess(ctx: ForgeParser.ContainerAccessContext): Value {
            return when (ctx.container.type) {
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
            return when {
                from == to -> value
                from == IntType && to == FloatType -> Int2Float(value)
                from == FloatType && to == IntType -> Float2Int(value)
                else -> Cast(value, to)
            }
        }

        override fun visitInstanceOf(ctx: ForgeParser.InstanceOfContext): Value {
            return IsInstanceOf(ctx.value.transform(), ctx.ty.toType())
        }

        override fun visitFunctionCall(ctx: ForgeParser.FunctionCallContext): Value {
            ctx.port?.let { return PortRefValue(port(it), ctx.expression().first().transform()) }
            if (ctx.flowBuiltin == "bound") return BoundCheck(ctx.expression().first().transform())

            val sym = ctx.symbol
            val args = ctx.expression().map { it.transform() }
            return when (sym) {
                is ConcreteNodeSymbol -> CreateNode(node(sym), args)
                is RecordSymbol -> CreateRecord(record(sym), args)
                is FunctionSymbol -> Call(function(sym), null, args)
                else -> throw AssertionError("call ${ctx.text} resolves to $sym")
            }
        }

        override fun visitMethodCall(ctx: ForgeParser.MethodCallContext): Value {
            val args = ctx.expression().drop(1).map { it.transform() }
            return when (val sym = ctx.symbol) {
                is EnumItemSymbol -> CreateEnum(enumItem(sym), args)
                is FunctionSymbol -> Call(function(sym), ctx.receiver.transform(), args)
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

        override fun visitContextualized(ctx: ForgeParser.ContextualizedContext?): Value? {
            throw UnsupportedOperationException("'where' is not supported yet")
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
            return ConstructorPattern(
                enumItem(ctx.anySymbol as EnumItemSymbol),
                ctx.patternArg().map { it.transformPattern() }
            )
        }

        override fun visitChildren(node: org.antlr.v4.runtime.tree.RuleNode): MatchPattern {
            // labeled pattern arguments (`#typePatternArg`, ...) wrap exactly one pattern
            return (node.getChild(0) as ParserRuleContext).transformPattern()
        }
    }

    private fun Type.nonNull(): Type = if (this is OptionalType) elem else this

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
