package com.leko.forge.llir

/**
 * Rewrites a value bottom up: the children are rewritten first, then [f] is applied to the value rebuilt from
 * them.
 */
fun Value.rewrite(f: (Value) -> Value): Value {
    fun Value.r() = rewrite(f)
    fun List<Value>.r() = map { it.rewrite(f) }
    val rebuilt: Value = when (this) {
        is IntValue, is LongValue, is FloatValue, is BoolValue, is StringValue, NullValue,
        is Parameter, is LocalRef, is This, is AccessParentInhAttr, is Panic,
        is PortValue, is FlowGraphRef -> this

        is ListLiteral -> copy(list = list.r())
        is MapLiteral -> copy(entries = entries.map { (k, v) -> k.r() to v.r() })
        is Call -> copy(receiver = receiver?.r(), args = args.r())
        is CreateRecord -> copy(args = args.r())
        is CreateEnum -> copy(args = args.r())
        is CreateNode -> copy(args = args.r())

        is AccessRecordField -> copy(accessed = accessed.r())
        is AccessNodeField -> copy(accessed = accessed.r())
        is AccessNodeAttr -> copy(receiver = receiver.r())
        is AccessListContainer -> copy(container = container.r(), key = key.r())
        is AccessMapContainer -> copy(container = container.r(), key = key.r())

        is Int2Float -> copy(value = value.r())
        is Float2Int -> copy(value = value.r())
        is Int2Long -> copy(value = value.r())
        is Long2Int -> copy(value = value.r())
        is Long2Float -> copy(value = value.r())
        is Float2Long -> copy(value = value.r())
        is Cast -> copy(value = value.r())
        is IsInstanceOf -> copy(value = value.r())

        is NegateInt -> copy(value = value.r())
        is NegateFloat -> copy(value = value.r())
        is InvertBool -> copy(value = value.r())
        is InvertInt -> copy(value = value.r())
        is IsNull -> copy(checked = checked.r())
        is NotNull -> copy(value = value.r())
        is Elvis -> copy(value = value.r(), alt = alt.r())

        is MulInt -> copy(lhs.r(), rhs.r())
        is DivInt -> copy(lhs.r(), rhs.r())
        is ModInt -> copy(lhs.r(), rhs.r())
        is AddInt -> copy(lhs.r(), rhs.r())
        is SubInt -> copy(lhs.r(), rhs.r())
        is AndInt -> copy(lhs.r(), rhs.r())
        is OrInt -> copy(lhs.r(), rhs.r())
        is XorInt -> copy(lhs.r(), rhs.r())
        is IAnd -> copy(lhs.r(), rhs.r())
        is ShlInt -> copy(lhs.r(), rhs.r())
        is ShrInt -> copy(lhs.r(), rhs.r())
        is UshrInt -> copy(lhs.r(), rhs.r())
        is SetEquals -> copy(lhs.r(), rhs.r())
        is Builtin -> copy(receiver = receiver?.r(), args = args.r())
        is IOr -> copy(lhs.r(), rhs.r())
        is MulFloat -> copy(lhs.r(), rhs.r())
        is DivFloat -> copy(lhs.r(), rhs.r())
        is AddFloat -> copy(lhs.r(), rhs.r())
        is SubFloat -> copy(lhs.r(), rhs.r())
        is LtInt -> copy(lhs.r(), rhs.r())
        is LtFloat -> copy(lhs.r(), rhs.r())
        is GtInt -> copy(lhs.r(), rhs.r())
        is GtFloat -> copy(lhs.r(), rhs.r())
        is GeqInt -> copy(lhs.r(), rhs.r())
        is GeqFloat -> copy(lhs.r(), rhs.r())
        is LeqInt -> copy(lhs.r(), rhs.r())
        is LeqFloat -> copy(lhs.r(), rhs.r())
        is AndBool -> copy(lhs.r(), rhs.r())
        is OrBool -> copy(lhs.r(), rhs.r())
        is XorBool -> copy(lhs.r(), rhs.r())
        is IntEquals -> copy(lhs.r(), rhs.r())
        is FloatEquals -> copy(lhs.r(), rhs.r())
        is BoolEquals -> copy(lhs.r(), rhs.r())
        is StringEquals -> copy(lhs.r(), rhs.r())
        is ListEquals -> copy(lhs.r(), rhs.r())
        is MapEquals -> copy(lhs.r(), rhs.r())
        is EnumEquals -> copy(lhs.r(), rhs.r())
        is RecordEquals -> copy(lhs.r(), rhs.r())
        is NodeEquals -> copy(lhs = lhs.r(), rhs = rhs.r())
        is ConcatStr -> copy(lhs.r(), rhs.r())
        is MergeLists -> copy(lhs.r(), rhs.r())
        is MergeMaps -> copy(lhs.r(), rhs.r())

        is InList -> copy(list = list.r(), element = element.r())
        is InMapKeys -> copy(map = map.r(), key = key.r())
        is AppendListItem -> copy(list = list.r(), element = element.r())
        is AppendAllListItems -> copy(list = list.r(), elements = elements.r())
        is PutMapItem -> copy(map = map.r(), key = key.r(), value = value.r())
        is PutAllMapItems -> copy(map = map.r(), elements = elements.r())

        is IfElse -> copy(cond = cond.r(), then = then.r(), alt = alt.r())

        is Let -> copy(value = value.r(), body = body.r())
        is Lambda -> copy(body = body.r())
        is CallValue -> copy(function = function.r(), args = args.r())
        is TupleLiteral -> copy(elems = elems.r())
        is TupleGet -> copy(tuple = tuple.r())

        is PortRefValue -> copy(child = child.r())
        is BoundCheck -> copy(port = port.r())

        is Match -> copy(
            scrutinee = scrutinee.r(),
            cases = cases.map { MatchCase(it.pattern.rewrite(f), it.guard?.r(), it.body.r()) },
            alt = alt?.r(),
        )
    }
    return f(rebuilt)
}

fun MatchPattern.rewrite(f: (Value) -> Value): MatchPattern = when (this) {
    WildcardPattern, is BindPattern -> this
    is ValuePattern -> ValuePattern(value.rewrite(f))
    is ConstructorPattern -> copy(args = args.map { it.rewrite(f) })
    is ListPattern -> ListPattern(elements.map { it.rewrite(f) })
    is SplitPattern -> SplitPattern(head.rewrite(f), tail.rewrite(f))
    is TuplePattern -> copy(elements = elements.map { it.rewrite(f) })
}

/** Calls [visit] for [this] value and every value in it. */
fun Value.forEachValue(visit: (Value) -> Unit) {
    rewrite { visit(it); it }
}

// ---- the values of declarations --------------------------------------------------------------------

/** Rewrites every value of the module (attribute equations, functions, flows, grammars) in place. */
fun LLIRModule.rewriteValues(f: (Value) -> Value) {
    fun Value.r() = rewrite(f)
    for (fn in functions) fn.body = fn.body?.r()
    for (inh in inherited.values) inh.init = inh.init.r()
    for (node in nodes) {
        for (syn in node.synthetics.values) syn.body = syn.body?.r()
        for (c in node.circulars.values) {
            c.init = c.init.r()
            c.body = c.body.r()
        }
        for (coll in node.collections.values) {
            coll.init = coll.init.r()
            coll.contributions.replaceAll { Contribution(it.id, it.source, it.coll, it.target.r(), it.alias, it.value.r()) }
        }
        for ((name, eq) in node.inhEquations.toMap()) {
            node.inhEquations[name] = InhEquation(eq.attrName, eq.delegates.map { InhDelegate(it.field, it.key?.r(), it.binder, it.value.r(), it.child.r()) })
        }
    }
    for (graph in graphs) graph.flows.replaceAll { FlowRule(it.node, it.binders, it.body.map { s -> s.rewrite(f) }) }
    for (parser in parsers) for (rule in parser.rules.values) {
        rule.alternatives.replaceAll { alt ->
            GrammarAlternative(alt.operator, alt.precedence, alt.options.map { GrammarOption(it.body.rewrite(f), it.producer?.r(), it.labels) })
        }
    }
}

fun FlowStmt.rewrite(f: (Value) -> Value): FlowStmt = when (this) {
    is FlowEntry -> FlowEntry(target.rewrite(f))
    is FlowConnect -> FlowConnect(source.rewrite(f), target.rewrite(f), label?.rewrite(f))
    is FlowVertex -> FlowVertex(name, label?.rewrite(f))
    is FlowSequence -> FlowSequence(items.rewrite(f))
    is FlowIf -> FlowIf(cond.rewrite(f), then.map { it.rewrite(f) }, alt.map { it.rewrite(f) })
    is FlowFor -> FlowFor(name, iterable.rewrite(f), body.map { it.rewrite(f) })
}

fun GExpr.rewrite(f: (Value) -> Value): GExpr = when (this) {
    is GTerminal, is GBits, is GNonterminal -> this
    is GPrimitive -> copy(argument = argument?.rewrite(f))
    is GRepeat -> copy(expr = expr.rewrite(f), count = count?.rewrite(f))
    is GPredicate -> copy(expr = expr.rewrite(f))
    is GChoice -> copy(alternatives = alternatives.map { it.rewrite(f) })
    is GSequence -> copy(items = items.map { GItem(it.label, it.expr.rewrite(f)) })
}

/** The nonterminals of a parsing expression. */
fun GExpr.forEachNonterminal(visit: (GNonterminal) -> Unit) {
    when (this) {
        is GNonterminal -> visit(this)
        is GRepeat -> expr.forEachNonterminal(visit)
        is GPredicate -> expr.forEachNonterminal(visit)
        is GChoice -> alternatives.forEach { it.forEachNonterminal(visit) }
        is GSequence -> items.forEach { it.expr.forEachNonterminal(visit) }
        else -> {}
    }
}
