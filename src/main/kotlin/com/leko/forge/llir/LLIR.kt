package com.leko.forge.llir

import ForgeParserBaseVisitor
import com.leko.forge.attr.*
import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.*


class LLIR private constructor(context: Context) {

    companion object {
        fun instance(context: Context): LLIR =
            context.getServices(LLIR::class) { LLIR(context) }
    }

    private val resolve = Resolve.instance(context)

    private val valueTransformer = ExpressionTransformer()

    private val matchTransformer = MatchTransformer()

    private val parameters = mutableMapOf<VarSymbol, Parameter>()

    private val locals = mutableMapOf<VarSymbol, Value>()

    private val nodes = mutableMapOf<String, Node>()
    private val enums = mutableMapOf<EnumSymbol, LLIREnum>()
    private val records = mutableMapOf<RecordSymbol, Record>()

    private val functions = mutableMapOf<Symbol, Function>()

    private fun parameter(sym: VarSymbol): Parameter {
        if (sym !in parameters) parameters[sym] = Parameter(sym.name, sym.type)
        return parameters[sym]!!
    }

    private fun node(sym: NodeSymbol): Node {
        val requireInjection = sym.name !in nodes
        val node = node(sym.name)
        if (requireInjection) {
            if (sym is ConcreteNodeSymbol) {
                sym.fields.forEach { node.fields[it.name] = it.type }
            }
            else {
                node.isInterface = true
            }
            for (parent in sym.parents) {
                node.parents.add(node(parent))
            }
        }
        return node
    }

    private fun node(name: String): Node {
        if (name !in nodes) {
            nodes[name] = Node(name)
        }
        return nodes[name]!!
    }

    private fun Node.executableAttr(sym: AttributeSymbol): ExecutableAttribute {
        return when (sym) {
            is SynSymbol -> this.synthetics.getValue(sym.name)
            is InhSymbol -> this.inherited.getOrPut(sym.name) { ExecutableAttribute(sym.name, sym.type, null) }
            is CollSymbol -> this.collections.getOrPut(sym.name) { ExecutableAttribute(sym.name, sym.type, null) }
        }
    }

    private fun record(sym: RecordSymbol): Record {
        if (sym !in records) records[sym] = Record(sym.name)
        return records[sym]!!
    }

    private fun enum(sym: EnumSymbol): LLIREnum {
        if (sym !in enums) enums[sym] = LLIREnum(
            sym.name,
            sym.items.map { EnumItem(it.enumType, it.name) }
        )
        return enums[sym]!!
    }

    private fun enumItem(enumItem: EnumItemSymbol): EnumItem {
        val enum = enum(resolve.resolve(enumItem.enumType) as EnumSymbol)
        return enum.enumItems.first { it.itemName == enumItem.name }
    }

    private fun struct(type: Type): Struct {
        if (type !is ComplexType) throw AssertionError()
        val name = type.name
        return nodes.values.find { it.name == name }
            ?: enums.values.find { it.name == name }
            ?: records.values.find { it.name == name }
            ?: throw AssertionError()
    }

    private fun function(sym: CallableSymbol): Function {
        if (sym !in functions) functions[sym] = Function(
            if (sym is FunctionSymbol && sym.receiver != null) sym.receiver.type else null,
            sym.name,
            sym.params.map { parameter(it) },
            sym.returnType,
            null
        )
        return functions[sym]!!
    }

    fun transform(ctx: ForgeParser.RootContext): LLIRModule {
        ctx.nodeDef().forEach { node(it.symbol!!) }
        ctx.function().forEach { function(it.symbol!!) }
        ctx.parseRuleDef().forEach { function(it.symbol!!) }
        ctx.enum_().forEach { enum(it.symbol!!) }

        ctx.synDef().forEach { transform(it) }

        return LLIRModule(
            ctx.start.tokenSource?.sourceName?.takeIf { it != "<unknown>" },
            ctx.record().map { transform(it) },
                ctx.enum_().map { transform(it) },
            ctx.nodeDef().map { transform(it) },
            ctx.function().map { transform(it) },
            ctx.inhDef().map { transform(it) }.groupBy { it.name }.mapValues { it.value.first() }.toMutableMap()
        )
    }

    fun transform(ctx: ForgeParser.RecordContext): Record {
        return record(ctx.symbol as RecordSymbol)
    }

    fun transform(ctx: ForgeParser.EnumContext): LLIREnum {
        return enum(ctx.symbol as EnumSymbol)
    }

    fun transform(ctx: ForgeParser.NodeDefContext): Node {
        return node(ctx.symbol as NodeSymbol)
    }

    fun transform(ctx: ForgeParser.SynDefContext) {
        val sym = ctx.symbol!!
        if (sym.owner !is ConcreteNodeSymbol) return
        node(sym.owner).synthetics[sym.name] = ExecutableAttribute(sym.name, sym.type, ctx.syn.transform())
    }

    fun transform(ctx: ForgeParser.InhDefContext): ExecutableAttribute {
        val sym = ctx.symbol!!
        return ExecutableAttribute(sym.name, sym.type, ctx.expression().transform())
    }

    fun transform(ctx: ForgeParser.FunctionContext): Function {
        val fn = function(ctx.symbol as FunctionSymbol)
        fn.body = ctx.expression()?.transform()
        return fn
    }


    private fun ForgeParser.ExpressionContext.transform(): Value {
        return this.accept(valueTransformer)
    }

    private fun ForgeParser.PatternContext.transform(): MatchPattern {
        return this.accept(matchTransformer)
    }

    private fun ForgeParser.PatternArgContext.transform(): MatchPattern {
        return this.accept(matchTransformer)
    }

    fun getLocal(sym: VarSymbol): Value {
        return locals[sym]!!
    }

    fun <T> withLocal(sym: VarSymbol, value: Value, op: () -> T): T {
        locals[sym] = value
        val r = op()
        locals.remove(sym)
        return r
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
            return StringValue(ctx.STRING().text)
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
            return MapLiteral(buildMap {
                ctx.expression().windowed(size = 2).forEach {
                    put(it.first().transform(), it.last().transform())
                }
            }, ctx.type)
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

        override fun visitMulDivMod(ctx: ForgeParser.MulDivModContext): Value {
            if (ctx.STAR() != null) {
                val lhsTy = ctx.left.type
                val rhsTy = ctx.right.type
                return when (lhsTy to rhsTy) {
                    IntType to IntType -> MulInt(ctx.left.transform(), ctx.right.transform())
                    FloatType to IntType -> MulFloat(ctx.left.transform(), Int2Float(ctx.right.transform()))
                    IntType to FloatType -> MulFloat(Int2Float(ctx.left.transform()), ctx.right.transform())
                    FloatType to FloatType -> MulFloat(ctx.left.transform(), ctx.right.transform())
                    else -> throw AssertionError()
                }
            }
            if (ctx.SLASH() != null) {
                val lhsTy = ctx.left.type
                val rhsTy = ctx.right.type
                return when (lhsTy to rhsTy) {
                    IntType to IntType -> DivInt(ctx.left.transform(), ctx.right.transform())
                    FloatType to IntType -> DivFloat(ctx.left.transform(), Int2Float(ctx.right.transform()))
                    IntType to FloatType -> DivFloat(Int2Float(ctx.left.transform()), ctx.right.transform())
                    FloatType to FloatType -> DivFloat(ctx.left.transform(), ctx.right.transform())
                    else -> throw AssertionError()
                }
            }
            if (ctx.PERCENT() != null) {
                return ModInt(ctx.left.transform(), ctx.right.transform())
            }
            throw AssertionError()
        }

        override fun visitAddSub(ctx: ForgeParser.AddSubContext): Value {
            if (ctx.PLUS() != null) {
                val lhsTy = ctx.left.type
                val rhsTy = ctx.right.type
                return when (lhsTy to rhsTy) {
                    IntType to IntType -> AddInt(ctx.left.transform(), ctx.right.transform())
                    FloatType to IntType -> AddFloat(ctx.left.transform(), Int2Float(ctx.right.transform()))
                    IntType to FloatType -> AddFloat(Int2Float(ctx.left.transform()), ctx.right.transform())
                    FloatType to FloatType -> AddFloat(ctx.left.transform(), ctx.right.transform())
                    StrType to StrType -> ConcatStr(ctx.left.transform(), ctx.right.transform())
                    else -> throw AssertionError()
                }
            }
            if (ctx.MINUS() != null) {
                val lhsTy = ctx.left.type
                val rhsTy = ctx.right.type
                return when (lhsTy to rhsTy) {
                    IntType to IntType -> SubInt(ctx.left.transform(), ctx.right.transform())
                    FloatType to IntType -> SubFloat(ctx.left.transform(), Int2Float(ctx.right.transform()))
                    IntType to FloatType -> SubFloat(Int2Float(ctx.left.transform()), ctx.right.transform())
                    FloatType to FloatType -> SubFloat(ctx.left.transform(), ctx.right.transform())
                    else -> throw AssertionError()
                }
            }
            throw AssertionError()
        }

        override fun visitLogicalAnd(ctx: ForgeParser.LogicalAndContext): Value {
            val lhsTy = ctx.left.type
            val rhsTy = ctx.right.type
            return when (lhsTy to rhsTy) {
                IntType to IntType -> AndInt(ctx.left.transform(), ctx.right.transform())
                BoolType to BoolType -> AndBool(ctx.left.transform(), ctx.right.transform())
                else -> throw AssertionError()
            }
        }

        override fun visitLogicalOr(ctx: ForgeParser.LogicalOrContext): Value {
            val lhsTy = ctx.left.type
            val rhsTy = ctx.right.type
            return when (lhsTy to rhsTy) {
                IntType to IntType -> OrInt(ctx.left.transform(), ctx.right.transform())
                BoolType to BoolType -> OrBool(ctx.left.transform(), ctx.right.transform())
                else -> throw AssertionError()
            }
        }

        override fun visitLogicalXor(ctx: ForgeParser.LogicalXorContext): Value {
            val lhsTy = ctx.left.type
            val rhsTy = ctx.right.type
            return when (lhsTy to rhsTy) {
                IntType to IntType -> XorInt(ctx.left.transform(), ctx.right.transform())
                BoolType to BoolType -> XorBool(ctx.left.transform(), ctx.right.transform())
                else -> throw AssertionError()
            }
        }

        override fun visitIand(ctx: ForgeParser.IandContext): Value {
            return IAnd(ctx.left.transform(), ctx.right.transform())
        }

        override fun visitIor(ctx: ForgeParser.IorContext): Value {
            return IOr(ctx.left.transform(), ctx.right.transform())
        }

        override fun visitRelational(ctx: ForgeParser.RelationalContext): Value {
            if (ctx.LT() != null) {
                val lhsTy = ctx.left.type
                val rhsTy = ctx.right.type
                return when (lhsTy to rhsTy) {
                    IntType to IntType -> LtInt(ctx.left.transform(), ctx.right.transform())
                    FloatType to IntType -> LtFloat(ctx.left.transform(), Int2Float(ctx.right.transform()))
                    IntType to FloatType -> LtFloat(Int2Float(ctx.left.transform()), ctx.right.transform())
                    FloatType to FloatType -> LtFloat(ctx.left.transform(), ctx.right.transform())
                    else -> throw AssertionError()
                }
            }
            if (ctx.GT() != null) {
                val lhsTy = ctx.left.type
                val rhsTy = ctx.right.type
                return when (lhsTy to rhsTy) {
                    IntType to IntType -> GtInt(ctx.left.transform(), ctx.right.transform())
                    FloatType to IntType -> GtFloat(ctx.left.transform(), Int2Float(ctx.right.transform()))
                    IntType to FloatType -> GtFloat(Int2Float(ctx.left.transform()), ctx.right.transform())
                    FloatType to FloatType -> GtFloat(ctx.left.transform(), ctx.right.transform())
                    else -> throw AssertionError()
                }
            }
            if (ctx.LEQ() != null) {
                val lhsTy = ctx.left.type
                val rhsTy = ctx.right.type
                return when (lhsTy to rhsTy) {
                    IntType to IntType -> LeqInt(ctx.left.transform(), ctx.right.transform())
                    FloatType to IntType -> LeqFloat(ctx.left.transform(), Int2Float(ctx.right.transform()))
                    IntType to FloatType -> LeqFloat(Int2Float(ctx.left.transform()), ctx.right.transform())
                    FloatType to FloatType -> LeqFloat(ctx.left.transform(), ctx.right.transform())
                    else -> throw AssertionError()
                }
            }
            if (ctx.GEQ() != null) {
                val lhsTy = ctx.left.type
                val rhsTy = ctx.right.type
                return when (lhsTy to rhsTy) {
                    IntType to IntType -> GeqInt(ctx.left.transform(), ctx.right.transform())
                    FloatType to IntType -> GeqFloat(ctx.left.transform(), Int2Float(ctx.right.transform()))
                    IntType to FloatType -> GeqFloat(Int2Float(ctx.left.transform()), ctx.right.transform())
                    FloatType to FloatType -> GeqFloat(ctx.left.transform(), ctx.right.transform())
                    else -> throw AssertionError()
                }
            }
            throw AssertionError()
        }

        override fun visitConcat(ctx: ForgeParser.ConcatContext): Value {
            val lhsTy = ctx.left.type
            val rhsTy = ctx.right.type
            if (lhsTy is ListType && rhsTy is ListType) {
                return MergeLists(ctx.left.transform(), ctx.right.transform())
            }
            if (lhsTy is MapType && rhsTy is MapType) {
                return MergeMaps(ctx.left.transform(), ctx.right.transform())
            }
            throw AssertionError()
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
            if (lhsTy is NullType && rhsTy is NullType) return BoolValue(true)
            if (lhsTy is NullType) return IsNull(ctx.right.transform())
            if (rhsTy is NullType) return IsNull(ctx.left.transform())
            val eq = when (lhsTy to rhsTy) {
                IntType to IntType -> IntEquals(ctx.left.transform(), ctx.right.transform())
                FloatType to IntType -> FloatEquals(Int2Float(ctx.left.transform()), ctx.right.transform())
                IntType to FloatType -> FloatEquals(ctx.left.transform(), Int2Float(ctx.right.transform()))
                FloatType to FloatType -> FloatEquals(ctx.left.transform(), ctx.right.transform())
                BoolType to BoolType -> BoolEquals(ctx.left.transform(), ctx.right.transform())
                StrType to StrType -> StringEquals(ctx.left.transform(), ctx.right.transform())
                else -> {
                    if (lhsTy is ListType && rhsTy is ListType) return ListEquals(ctx.left.transform(), ctx.right.transform())
                    if (lhsTy is MapType && rhsTy is MapType) return MapEquals(ctx.left.transform(), ctx.right.transform())
                    val lhsSym = resolve.resolve(lhsTy)
                    val rhsSym = resolve.resolve(rhsTy)
                    if (lhsSym is EnumSymbol && rhsSym is EnumSymbol) {
                        return EnumEquals(ctx.left.transform(), ctx.right.transform())
                    }
                    if (lhsSym is NodeSymbol && rhsSym is NodeSymbol) {
                        return NodeEquals(
                            ctx.left.transform(),
                            lhsSym is ConcreteNodeSymbol,
                            ctx.right.transform(),
                            rhsSym is ConcreteNodeSymbol
                        )
                    }
                    if (lhsSym is RecordSymbol && rhsSym is RecordSymbol) {
                        return RecordEquals(ctx.left.transform(), ctx.right.transform())
                    }
                    throw AssertionError()
                }
            }
            return if (ctx.NOTEQUALS() != null) InvertBool(eq) else eq
        }

        override fun visitInOp(ctx: ForgeParser.InOpContext): Value {
            val op = if (ctx.left.type is ListType) {
                InList(ctx.left.transform(), ctx.right.transform())
            }
            else {
                InMapKeys(ctx.left.transform(), ctx.right.transform())
            }
            return if (ctx.NOTIN() != null) InvertBool(op) else op
        }

        override fun visitElvis(ctx: ForgeParser.ElvisContext): Value {
            val checked = ctx.expr.transform()
            val alt = ctx.alt.transform()
            return IfElse(IsNull(checked), alt, checked, ctx.type)
        }

        override fun visitNullCheck(ctx: ForgeParser.NullCheckContext): Value {
            val checked = ctx.value.transform()
            return IfElse(IsNull(checked), Panic("value is null"), checked, ctx.type)
        }

        override fun visitIdentifier(ctx: ForgeParser.IdentifierContext): Value {
            val sym = ctx.symbol
            if (sym is VarSymbol) {
                return if (sym.varKind == VarSymbol.VarKind.PARAMETER) {
                    parameter(sym)
                } else if (sym.varKind == VarSymbol.VarKind.PARENT_INH) {
                    AccessParentInhAttr(sym.name, sym.type)
                } else {
                    assert(sym.varKind == VarSymbol.VarKind.VARIABLE)
                    getLocal(sym)
                }
            }
            throw AssertionError(sym?.name)
        }

        override fun visitFieldAccess(ctx: ForgeParser.FieldAccessContext): Value {
            val sym = ctx.symbol
            val recvTy = ctx.receiver.type
            if (sym is VarSymbol) {
                return when (sym.varKind) {
                    VarSymbol.VarKind.RECORD_FIELD -> AccessRecordField(record(resolve.resolve(recvTy) as RecordSymbol), ctx.receiver.transform(), ctx.name.text)
                    VarSymbol.VarKind.NODE_FIELD -> AccessNodeField(node(resolve.resolve(recvTy) as NodeSymbol), ctx.receiver.transform(), ctx.name.text)
                    VarSymbol.VarKind.PARENT_INH -> AccessParentInhAttr(sym.name, sym.type)
                    else -> throw AssertionError("got ${sym.varKind} (${sym})")
                }
            }
            if (sym is EnumItemSymbol) {
                return CreateEnum(enumItem(sym), emptyList())
            }
            if (sym is InhSymbol) {
                val node = node((recvTy as ComplexType).name)
                val attr = node.executableAttr(sym)
                return AccessNodeAttr(node, ctx.receiver.transform(), attr)
            }
            if (sym is NodeBoundAttributeSymbol) {
                assert(recvTy is ComplexType) { "Node type must be represented as ComplexType" }
                val node = node(sym.owner.name)
                val attr = node.executableAttr(sym)
                return AccessNodeAttr(node, ctx.receiver.transform(), attr)
            }
            throw AssertionError("got $sym")
        }

        override fun visitContainerAccess(ctx: ForgeParser.ContainerAccessContext): Value {
            val type = ctx.type
            if (type is ListType) {
                return AccessListContainer(ctx.container.transform(), ctx.key.transform())
            }
            if (type is MapType) {
                return AccessMapContainer(ctx.container.transform(), ctx.key.transform())
            }
            throw AssertionError()
        }

        override fun visitListComprehension(ctx: ForgeParser.ListComprehensionContext): Value {
            if (ctx.WITH() == null) {
                val iter = ctx.iterable.transform()
                val mapRef = MapRef(ctx.name.text, (iter.type as ListType).elem)
                val bodyVal = withLocal(ctx.symbol as VarSymbol, mapRef) { ctx.put.transform() }
                return MapOp(iter, mapRef, bodyVal)
            }
            else {
                val (iterSym, accSym) = ctx.symbols!!
                val item = FoldItem(ctx.name.text, iterSym.type)
                val acc = FoldAcc(ctx.acc.text, accSym.type)
                val bodyVal = withLocal(iterSym, item) {
                    withLocal(accSym, item) {
                        ctx.put.transform()
                    }
                }
                return FoldOp(ctx.iterable.transform(), ctx.accInit.transform(), item, acc, bodyVal)
            }
        }

        override fun visitCast(ctx: ForgeParser.CastContext): Value {
            val value = ctx.value.transform()
            if (ctx.value.type == ctx.type) return value
            if (ctx.value.type is IntType) {
                if (ctx.type == FloatType) return Int2Float(value)
                throw AssertionError()
            }
            if (ctx.value.type is FloatType) {
                if (ctx.type == IntType) return Float2Int(value)
                throw AssertionError()
            }
            return Cast(value, struct(ctx.type))
        }

        override fun visitFunctionCall(ctx: ForgeParser.FunctionCallContext): Value {
            val sym = ctx.symbol
            val args = ctx.expression().map { it.transform() }

            return when (sym) {
                is ConcreteNodeSymbol -> CreateNode(node(sym), args)
                is RecordSymbol -> CreateRecord(record(sym), args)
                is FunctionSymbol -> Call(function(sym), null, args)
                else -> throw AssertionError()
            }
        }

        override fun visitMethodCall(ctx: ForgeParser.MethodCallContext): Value {
            return when (val sym = ctx.symbol) {
                is EnumItemSymbol -> CreateEnum(enumItem(sym), ctx.expression().drop(1).map { it.transform() })
                is FunctionSymbol -> Call(function(sym), ctx.receiver.transform(), ctx.expression().map { it.transform() })
                is ParserRuleSymbol -> Call(function(sym), null, ctx.expression().drop(1).map { it.transform() })
                else -> throw AssertionError()
            }
        }

        override fun visitMatch(ctx: ForgeParser.MatchContext): Value {
            return Match(
                ctx.switch_.transform(),
                ctx.matchCase().map { it.transform() },
                ctx.alt?.transform(),
                ctx.type
            )
        }

        override fun visitContextualized(ctx: ForgeParser.ContextualizedContext?): Value? {
            throw AssertionError("should be transformed earlier")
        }

    }

    private fun ForgeParser.MatchCaseContext.transform(): MatchCase {
        return MatchCase(
            this.pattern().transform(),
            this.cond?.transform(),
            this.value.transform()
        )
    }

    private inner class MatchTransformer : ForgeParserBaseVisitor<MatchPattern>() {

        override fun visitWildcardPattern(ctx: ForgeParser.WildcardPatternContext): MatchPattern {
            return WildcardPattern
        }

        override fun visitPatternVariable(ctx: ForgeParser.PatternVariableContext): MatchPattern {
            return BindPattern(ctx.IDENT().text)
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
