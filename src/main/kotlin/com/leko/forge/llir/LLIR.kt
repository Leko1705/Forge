package com.leko.forge.llir

import ForgeParser
import ForgeParserBaseVisitor
import com.leko.forge.attr.*
import com.leko.forge.context.Context
import com.leko.forge.lr.ParserDefinition
import com.leko.forge.lr.ProductionAction
import com.leko.forge.model.*
import com.leko.forge.types.*
import com.leko.forge.utils.unescapeStringLiteral


/**
 * Lowers the attributed parse tree of a Forge program into the LLIR, a typed,
 * target independent representation of all declarations and expressions.
 */
class LLIR private constructor(context: Context) {

    companion object {
        fun instance(context: Context): LLIR =
            context.getServices(LLIR::class) { LLIR(context) }
    }

    private val resolve = Resolve.instance(context)

    private val symTab = SymTab.instance(context)

    private val typeMap = TypeMap.instance(context)

    private val valueTransformer = ExpressionTransformer()

    private val matchTransformer = MatchTransformer()

    private val nodes = linkedMapOf<String, Node>()
    private val enums = linkedMapOf<String, LLIREnum>()
    private val records = linkedMapOf<String, Record>()
    private val functions = linkedMapOf<Symbol, Function>()
    private val inherited = linkedMapOf<String, InhAttribute>()
    private val collections = linkedMapOf<CollSymbol, CollAttribute>()

    // ------------------------------------------------------------------
    // declarations
    // ------------------------------------------------------------------

    private fun node(sym: NodeSymbol): Node = nodes.getValue(sym.name)

    private fun node(name: String): Node = nodes.getValue(name)

    private fun enumItem(sym: EnumItemSymbol): EnumItem =
        enums.getValue(sym.enumType).items.first { it.itemName == sym.name }

    private fun record(sym: RecordSymbol): Record = records.getValue(sym.name)

    private fun function(sym: CallableSymbol): Function = functions.getOrPut(sym) {
        Function(
            if (sym is FunctionSymbol) sym.receiver?.type else null,
            sym.name,
            sym.params.map { Parameter(it.name, it.type) },
            sym.returnType,
        )
    }

    private fun attribute(sym: AttributeSymbol): Attribute = when (sym) {
        is SynSymbol -> node(sym.owner).synthetics.getValue(sym.name)
        is InhSymbol -> inherited.getValue(sym.name)
        is CollSymbol -> collections.getValue(sym)
    }

    private fun declare() {
        for (enum in symTab.enums.values) {
            val e = LLIREnum(enum.name)
            for (item in enum.items) {
                val i = EnumItem(enum.name, item.name)
                item.parameters.forEach { (name, type) -> i.fields[name] = type }
                e.items += i
            }
            enums[enum.name] = e
        }
        for (record in symTab.table.values.filterIsInstance<RecordSymbol>()) {
            if (record.span == null) continue // primitive
            val r = Record(record.name)
            record.fields.forEach { r.fields[it.name] = it.type }
            records[record.name] = r
        }
        val nodeSymbols = symTab.table.values.filterIsInstance<NodeSymbol>()
        for (sym in nodeSymbols) {
            val n = Node(sym.name)
            if (sym is ConcreteNodeSymbol) sym.fields.forEach { n.fields[it.name] = it.type } else n.isInterface = true
            nodes[sym.name] = n
        }
        for (sym in nodeSymbols) {
            sym.parents.distinct().forEach { node(sym).parents += node(it) }
        }
        for ((nodeSym, attrs) in typeMap.nodeAttributes) {
            val n = node(nodeSym)
            for (attr in attrs) {
                when (attr) {
                    is SynSymbol -> n.synthetics[attr.name] = SynAttribute(n, attr.name, attr.type)
                    is CollSymbol -> CollAttribute(n, attr.name, attr.type).also {
                        n.collections[attr.name] = it
                        collections[attr] = it
                    }
                }
            }
        }
        for (inh in symTab.inheritedAttributes.values) {
            inherited[inh.name] = InhAttribute(inh.name, inh.type)
        }
    }

    fun transform(ctx: ForgeParser.RootContext, parsers: List<ParserDefinition>): LLIRModule {
        declare()

        for (synDef in ctx.synDef()) {
            val sym = synDef.symbol ?: continue
            if (synDef.syn == null) continue
            node(sym.owner).synthetics.getValue(sym.name).body = synDef.syn.transform()
        }
        for (inhDef in ctx.inhDef()) {
            val sym = inhDef.symbol ?: continue
            inherited.getValue(sym.name).init = coerce(inhDef.init.transform(), sym.type)
        }
        for (inhImpl in ctx.inhImpl()) {
            val sym = inhImpl.symbol ?: continue
            val n = node(inhImpl.node.text)
            val delegates = inhImpl.inhDelegate().map { delegate ->
                val binder = delegate.binder
                InhDelegate(
                    delegate.child.text,
                    if (binder == null) delegate.key?.transform() else null,
                    binder?.let { Local(it.name, it.type) },
                    coerce(delegate.value.transform(), sym.type),
                )
            }
            val previous = n.inhEquations[sym.name]
            n.inhEquations[sym.name] = InhEquation(sym.name, (previous?.delegates ?: emptyList()) + delegates)
        }
        for (coll in ctx.coll()) {
            val sym = coll.symbol ?: continue
            collections.getValue(sym).init = coerce(coll.init.transform(), sym.type)
        }
        for (contrib in ctx.contrib()) {
            val sym = contrib.symbol ?: continue
            val coll = collections.getValue(sym)
            val source = node(contrib.node.text)
            val contribution = Contribution(
                source,
                coll,
                contrib.receiver.transform(),
                Local(contrib.alias?.text ?: sym.name, sym.type),
                coerce(contrib.value.transform(), sym.type),
            )
            coll.contributions += contribution
            source.contributions += contribution
        }

        val topLevel = mutableListOf<Function>()
        for (func in ctx.function()) {
            val sym = func.symbol ?: continue
            val fn = function(sym)
            if (func.EXTERNAL() != null) {
                topLevel += Function(fn.receiver, fn.name, fn.parameters, fn.returnType, isExternal = true)
                continue
            }
            fn.body = func.expression()?.let { coerce(it.transform(), sym.returnType) }
            topLevel += fn
        }

        val llirParsers = parsers.map { def ->
            LLIRParser(def.symbol.name, def, def.actions.map { action ->
                when (action) {
                    is ProductionAction.Helper -> LLIRProduction.Helper(action.value)
                    is ProductionAction.User -> LLIRProduction.User(
                        action.labels.map { (sym, sem) -> Local(sym.name, sym.type) to sem },
                        action.option.producer?.let { coerce(it.transform(), action.type) },
                        action.value,
                        action.type,
                    )
                }
            })
        }

        return LLIRModule(
            ctx.start.tokenSource?.sourceName?.takeIf { it != "<unknown>" },
            records.values.toList(),
            enums.values.toList(),
            nodes.values.toList(),
            topLevel,
            inherited,
            llirParsers,
        )
    }

    private fun ForgeParser.ExpressionContext.transform(): Value {
        return this.accept(valueTransformer)
    }

    private fun ForgeParser.PatternContext.transform(): MatchPattern = this.accept(matchTransformer)

    private fun ForgeParser.PatternArgContext.transform(): MatchPattern = this.accept(matchTransformer)

    /** Inserts the implicit int to float conversion where a float is expected. */
    private fun coerce(value: Value, expected: Type): Value {
        val target = if (expected is OptionalType) expected.elem else expected
        return if (target == FloatType && value.type == IntType) Int2Float(value) else value
    }

    private fun isNode(type: Type): Boolean {
        val t = if (type is OptionalType) type.elem else type
        return t is ComplexType && resolve.resolve(t) is NodeSymbol
    }

    private inner class ExpressionTransformer : ForgeParserBaseVisitor<Value>() {

        override fun visitIntValue(ctx: ForgeParser.IntValueContext): Value {
            return IntValue(ctx.value.text.toInt())
        }

        override fun visitFloatValue(ctx: ForgeParser.FloatValueContext): Value {
            return FloatValue(ctx.value.text.toDouble())
        }

        override fun visitBoolValue(ctx: ForgeParser.BoolValueContext): Value {
            return BoolValue(ctx.TRUE() != null)
        }

        override fun visitStringValue(ctx: ForgeParser.StringValueContext): Value {
            return StringValue(unescapeStringLiteral(ctx.STRING().text))
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
            val type = ctx.type as ListType
            return ListLiteral(ctx.expression().map { coerce(it.transform(), type.elem) }, type)
        }

        override fun visitMapLiteral(ctx: ForgeParser.MapLiteralContext): Value {
            val type = ctx.type as MapType
            return MapLiteral(ctx.expression().chunked(2).map {
                coerce(it[0].transform(), type.key) to coerce(it[1].transform(), type.value)
            }, type)
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

        /** both operands, converted to float if one of them is a float */
        private fun numeric(left: ForgeParser.ExpressionContext, right: ForgeParser.ExpressionContext): Triple<Value, Value, Boolean> {
            val l = left.transform()
            val r = right.transform()
            val isFloat = left.type == FloatType || right.type == FloatType
            return Triple(
                if (isFloat && left.type == IntType) Int2Float(l) else l,
                if (isFloat && right.type == IntType) Int2Float(r) else r,
                isFloat,
            )
        }

        override fun visitMulDivMod(ctx: ForgeParser.MulDivModContext): Value {
            val (l, r, isFloat) = numeric(ctx.left, ctx.right)
            return when {
                ctx.PERCENT() != null -> ModInt(l, r)
                ctx.STAR() != null -> if (isFloat) MulFloat(l, r) else MulInt(l, r)
                else -> if (isFloat) DivFloat(l, r) else DivInt(l, r)
            }
        }

        override fun visitAddSub(ctx: ForgeParser.AddSubContext): Value {
            if (ctx.type == StrType) return ConcatStr(ctx.left.transform(), ctx.right.transform())
            val (l, r, isFloat) = numeric(ctx.left, ctx.right)
            return if (ctx.PLUS() != null) {
                if (isFloat) AddFloat(l, r) else AddInt(l, r)
            } else {
                if (isFloat) SubFloat(l, r) else SubInt(l, r)
            }
        }

        override fun visitLogicalAnd(ctx: ForgeParser.LogicalAndContext): Value =
            if (ctx.type == IntType) AndInt(ctx.left.transform(), ctx.right.transform())
            else AndBool(ctx.left.transform(), ctx.right.transform())

        override fun visitLogicalOr(ctx: ForgeParser.LogicalOrContext): Value =
            if (ctx.type == IntType) OrInt(ctx.left.transform(), ctx.right.transform())
            else OrBool(ctx.left.transform(), ctx.right.transform())

        override fun visitLogicalXor(ctx: ForgeParser.LogicalXorContext): Value =
            if (ctx.type == IntType) XorInt(ctx.left.transform(), ctx.right.transform())
            else XorBool(ctx.left.transform(), ctx.right.transform())

        override fun visitIand(ctx: ForgeParser.IandContext): Value {
            return IAnd(ctx.left.transform(), ctx.right.transform())
        }

        override fun visitIor(ctx: ForgeParser.IorContext): Value {
            return IOr(ctx.left.transform(), ctx.right.transform())
        }

        override fun visitRelational(ctx: ForgeParser.RelationalContext): Value {
            if (ctx.left.type == StrType) {
                val cmp = Call(Function(StrType, "compareTo", listOf(Parameter("other", StrType)), IntType), ctx.left.transform(), listOf(ctx.right.transform()))
                return when (ctx.op.type) {
                    ForgeParser.LT -> LtInt(cmp, IntValue(0))
                    ForgeParser.GT -> GtInt(cmp, IntValue(0))
                    ForgeParser.LEQ -> LeqInt(cmp, IntValue(0))
                    else -> GeqInt(cmp, IntValue(0))
                }
            }
            val (l, r, isFloat) = numeric(ctx.left, ctx.right)
            return when (ctx.op.type) {
                ForgeParser.LT -> if (isFloat) LtFloat(l, r) else LtInt(l, r)
                ForgeParser.GT -> if (isFloat) GtFloat(l, r) else GtInt(l, r)
                ForgeParser.LEQ -> if (isFloat) LeqFloat(l, r) else LeqInt(l, r)
                else -> if (isFloat) GeqFloat(l, r) else GeqInt(l, r)
            }
        }

        override fun visitConcat(ctx: ForgeParser.ConcatContext): Value {
            return if (ctx.type is MapType) MergeMaps(ctx.left.transform(), ctx.right.transform())
            else MergeLists(ctx.left.transform(), ctx.right.transform())
        }

        override fun visitIfElse(ctx: ForgeParser.IfElseContext): Value {
            return IfElse(
                ctx.cond.transform(),
                coerce(ctx.then.transform(), ctx.type),
                coerce(ctx.alt.transform(), ctx.type),
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
                lhsTy in listOf(IntType, FloatType) && rhsTy in listOf(IntType, FloatType) -> {
                    val (l, r, isFloat) = numeric(ctx.left, ctx.right)
                    if (isFloat) FloatEquals(l, r) else IntEquals(l, r)
                }
                isNode(lhsTy) || isNode(rhsTy) -> NodeEquals(ctx.left.transform(), true, ctx.right.transform(), true)
                else -> ValueEquals(ctx.left.transform(), ctx.right.transform())
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
            return Elvis(ctx.expr.transform(), coerce(ctx.alt.transform(), ctx.type), ctx.type)
        }

        override fun visitNullCheck(ctx: ForgeParser.NullCheckContext): Value {
            return NotNull(ctx.value.transform(), ctx.type)
        }

        override fun visitIdentifier(ctx: ForgeParser.IdentifierContext): Value {
            val sym = ctx.symbol
            if (sym is VarSymbol) {
                return if (sym.varKind == VarSymbol.VarKind.PARAMETER) {
                    Parameter(sym.name, sym.type)
                } else {
                    Local(sym.name, sym.type)
                }
            }
            throw AssertionError("unexpected identifier ${sym?.name}")
        }

        override fun visitFieldAccess(ctx: ForgeParser.FieldAccessContext): Value {
            val sym = ctx.symbol
            val recvTy = ctx.receiver.type
            if (sym is VarSymbol) {
                return when (sym.varKind) {
                    VarSymbol.VarKind.RECORD_FIELD -> {
                        val recordSym = resolve.resolve(recvTy) as RecordSymbol
                        if (recordSym.span == null) AccessBuiltinField(ctx.receiver.transform(), ctx.name.text, sym.type)
                        else AccessRecordField(record(recordSym), ctx.receiver.transform(), ctx.name.text, sym.type)
                    }
                    VarSymbol.VarKind.NODE_BUILTIN -> AccessBuiltinField(ctx.receiver.transform(), if (sym.name == "line") "forgeLine" else "forgeColumn", IntType)
                    VarSymbol.VarKind.NODE_FIELD -> AccessNodeField(node(resolve.resolve(recvTy) as NodeSymbol), ctx.receiver.transform(), ctx.name.text, sym.type)
                    else -> throw AssertionError("got ${sym.varKind} (${sym})")
                }
            }
            if (sym is EnumItemSymbol) {
                return CreateEnum(enumItem(sym), emptyList())
            }
            if (sym is AttributeSymbol) {
                return AccessNodeAttr(ctx.receiver.transform(), attribute(sym))
            }
            throw AssertionError("got $sym")
        }

        override fun visitContainerAccess(ctx: ForgeParser.ContainerAccessContext): Value {
            val containerType = ctx.container.type
            if (containerType is MapType) {
                return AccessMapContainer(ctx.container.transform(), ctx.key.transform(), ctx.type)
            }
            return AccessListContainer(ctx.container.transform(), ctx.key.transform(), ctx.type)
        }

        override fun visitListComprehension(ctx: ForgeParser.ListComprehensionContext): Value {
            if (ctx.WITH() == null) {
                val iterSym = ctx.symbol as VarSymbol
                return MapOp(ctx.iterable.transform(), Local(iterSym.name, iterSym.type), ctx.put.transform(), ctx.type)
            }
            else {
                val (iterSym, accSym) = ctx.symbols!!
                return FoldOp(
                    ctx.iterable.transform(),
                    coerce(ctx.accInit.transform(), accSym.type),
                    Local(iterSym.name, iterSym.type),
                    Local(accSym.name, accSym.type),
                    coerce(ctx.put.transform(), accSym.type),
                )
            }
        }

        override fun visitCast(ctx: ForgeParser.CastContext): Value {
            val value = ctx.value.transform()
            if (ctx.value.type == ctx.type) return value
            if (ctx.value.type == IntType && ctx.type == FloatType) return Int2Float(value)
            if (ctx.value.type == FloatType && ctx.type == IntType) return Float2Int(value)
            return Cast(value, ctx.type)
        }

        private fun arguments(args: List<ForgeParser.ExpressionContext>, sym: CallableSymbol): List<Value> =
            args.mapIndexed { i, arg -> coerce(arg.transform(), sym.params.getOrNull(i)?.type ?: arg.type) }

        override fun visitFunctionCall(ctx: ForgeParser.FunctionCallContext): Value {
            val sym = ctx.symbol!!
            val args = arguments(ctx.expression(), sym)

            return when (sym) {
                is ConcreteNodeSymbol -> CreateNode(node(sym), args)
                is RecordSymbol -> CreateRecord(record(sym), args)
                is FunctionSymbol -> Call(function(sym), null, args)
                else -> throw AssertionError()
            }
        }

        override fun visitMethodCall(ctx: ForgeParser.MethodCallContext): Value {
            val args = ctx.expression().drop(1)
            return when (val sym = ctx.symbol) {
                is EnumItemSymbol -> CreateEnum(enumItem(sym), args.mapIndexed { i, arg -> coerce(arg.transform(), sym.parameters[i].second) })
                is FunctionSymbol -> Call(function(sym), ctx.receiver.transform(), arguments(args, sym))
                is ParserRuleSymbol -> {
                    // the parser named in the call (rules may be inherited from a parent parser)
                    val parser = (ctx.receiver.type as? ParserType)?.name ?: sym.parser.name
                    ParseCall(parser, sym.name, args.first().transform(), sym.returnType)
                }
                else -> throw AssertionError()
            }
        }

        override fun visitMatch(ctx: ForgeParser.MatchContext): Value {
            return Match(
                ctx.switch_.transform(),
                ctx.matchCase().map {
                    MatchCase(
                        it.pattern().transform(),
                        it.cond?.transform(),
                        coerce(it.value.transform(), ctx.type),
                    )
                },
                ctx.alt?.let { coerce(it.transform(), ctx.type) },
                ctx.type
            )
        }

        override fun visitContextualized(ctx: ForgeParser.ContextualizedContext): Value {
            val sym = ctx.symbol!!
            val fn = function(sym)
            fn.body = ctx.`fun`.expression()?.let { coerce(it.transform(), sym.returnType) }
            return WithLocalFunction(fn, ctx.expr.transform())
        }

    }

    private inner class MatchTransformer : ForgeParserBaseVisitor<MatchPattern>() {

        override fun visitWildcardPattern(ctx: ForgeParser.WildcardPatternContext): MatchPattern {
            return WildcardPattern
        }

        override fun visitPatternVariable(ctx: ForgeParser.PatternVariableContext): MatchPattern {
            val sym = ctx.symbol!!
            return BindPattern(Local(sym.name, sym.type))
        }

        override fun visitListPattern(ctx: ForgeParser.ListPatternContext): MatchPattern {
            return ListPattern(ctx.patternArg().map { it.transform() })
        }

        override fun visitSplitPattern(ctx: ForgeParser.SplitPatternContext): MatchPattern {
            return SplitPattern(ctx.element.transform(), ctx.rest.transform())
        }

        override fun visitSplitPatternArg(ctx: ForgeParser.SplitPatternArgContext): MatchPattern {
            return SplitPattern(ctx.element.transform(), ctx.rest.transform())
        }

        override fun visitExprPattern(ctx: ForgeParser.ExprPatternContext): MatchPattern {
            return ValuePattern(ctx.expression().transform())
        }

        override fun visitTypePattern(ctx: ForgeParser.TypePatternContext): MatchPattern {
            return ConstructorPattern(
                EnumVariant(enumItem(ctx.symbol!! as EnumItemSymbol)),
                ctx.patternArg().map { it.transform() }
            )
        }

    }


}
