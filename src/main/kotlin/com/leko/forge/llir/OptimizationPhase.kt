package com.leko.forge.llir

import com.leko.forge.model.GRAPH
import com.leko.forge.types.*

/** A transformation of a module that keeps its meaning; [apply] returns the module and whether it changed. */
abstract class OptimizationPhase {
    abstract val name: String
    abstract fun apply(module: LLIRModule): Pair<LLIRModule, Boolean>
}

/**
 * Computes what is known when Forge runs: arithmetic, comparisons and logic on literals, `if` on a known
 * condition, `?:`, `!!` and `== null` on literals, the size of, the elements of and `in` on lists of literals.
 * Values are computed as the generated code would (Kotlin's `Int` and `Float`); nothing that fails at run time
 * (a division by zero, an index out of bounds) and no float that is not finite is folded.
 */
object ConstantFolding : OptimizationPhase() {
    override val name: String get() = "constant folding"

    override fun apply(module: LLIRModule): Pair<LLIRModule, Boolean> {
        var changed = false
        module.rewriteValues { v ->
            val folded = fold(v)
            if (folded !== v) changed = true
            folded
        }
        return module to changed
    }

    private fun isLiteral(v: Value) = v is IntValue || v is FloatValue || v is BoolValue || v is StringValue || v == NullValue

    private fun int(v: Value) = (v as? IntValue)?.value
    private fun float(v: Value) = (v as? FloatValue)?.value
    private fun bool(v: Value) = (v as? BoolValue)?.value
    private fun str(v: Value) = (v as? StringValue)?.value

    private fun ints(v: BinaryValue, f: (Int, Int) -> Int): Value {
        val a = int(v.lhs) ?: return v
        val b = int(v.rhs) ?: return v
        return IntValue(f(a, b))
    }

    private fun floats(v: BinaryValue, f: (Float, Float) -> Float): Value {
        val a = float(v.lhs) ?: return v
        val b = float(v.rhs) ?: return v
        val r = f(a, b)
        return if (r.isFinite()) FloatValue(r) else v
    }

    private fun intTest(v: BinaryValue, f: (Int, Int) -> Boolean): Value {
        val a = int(v.lhs) ?: return v
        val b = int(v.rhs) ?: return v
        return BoolValue(f(a, b))
    }

    private fun floatTest(v: BinaryValue, f: (Float, Float) -> Boolean): Value {
        val a = float(v.lhs) ?: return v
        val b = float(v.rhs) ?: return v
        return BoolValue(f(a, b))
    }

    /** A literal list, with the Kotlin values of its elements. */
    private fun literals(v: Value): List<Any?>? {
        if (v !is ListLiteral || !v.list.all { isLiteral(it) }) return null
        return v.list.map { e -> if (e == NullValue) null else int(e) ?: float(e) ?: bool(e) ?: str(e) }
    }

    private fun kotlinValue(v: Value): Any? = if (v == NullValue) null else int(v) ?: float(v) ?: bool(v) ?: str(v)

    fun fold(v: Value): Value = when (v) {
        is MulInt -> ints(v) { a, b -> a * b }
        is AddInt -> ints(v) { a, b -> a + b }
        is SubInt -> ints(v) { a, b -> a - b }
        is DivInt -> if (int(v.rhs) == 0) v else ints(v) { a, b -> a / b }
        is ModInt -> if (int(v.rhs) == 0) v else ints(v) { a, b -> a % b }
        is AndInt -> ints(v) { a, b -> a and b }
        is IAnd -> ints(v) { a, b -> a and b }
        is OrInt -> ints(v) { a, b -> a or b }
        is IOr -> ints(v) { a, b -> a or b }
        is XorInt -> ints(v) { a, b -> a xor b }
        is NegateInt -> int(v.value)?.let { IntValue(-it) } ?: v
        is InvertInt -> int(v.value)?.let { IntValue(it.inv()) } ?: v

        is MulFloat -> floats(v) { a, b -> a * b }
        is DivFloat -> floats(v) { a, b -> a / b }
        is AddFloat -> floats(v) { a, b -> a + b }
        is SubFloat -> floats(v) { a, b -> a - b }
        is NegateFloat -> float(v.value)?.let { FloatValue(-it) } ?: v
        is Int2Float -> int(v.value)?.let { FloatValue(it.toFloat()) } ?: v
        is Float2Int -> float(v.value)?.let { IntValue(it.toInt()) } ?: v

        is LtInt -> intTest(v) { a, b -> a < b }
        is GtInt -> intTest(v) { a, b -> a > b }
        is LeqInt -> intTest(v) { a, b -> a <= b }
        is GeqInt -> intTest(v) { a, b -> a >= b }
        is IntEquals -> intTest(v) { a, b -> a == b }
        is LtFloat -> floatTest(v) { a, b -> a < b }
        is GtFloat -> floatTest(v) { a, b -> a > b }
        is LeqFloat -> floatTest(v) { a, b -> a <= b }
        is GeqFloat -> floatTest(v) { a, b -> a >= b }
        is FloatEquals -> floatTest(v) { a, b -> a == b }
        is BoolEquals -> if (bool(v.lhs) != null && bool(v.rhs) != null) BoolValue(bool(v.lhs) == bool(v.rhs)) else v
        is StringEquals -> if (str(v.lhs) != null && str(v.rhs) != null) BoolValue(str(v.lhs) == str(v.rhs)) else v

        // the right operand is only evaluated if the left one does not decide
        is AndBool -> when (bool(v.lhs)) {
            false -> BoolValue(false)
            true -> v.rhs
            null -> v
        }
        is OrBool -> when (bool(v.lhs)) {
            true -> BoolValue(true)
            false -> v.rhs
            null -> v
        }
        is XorBool -> if (bool(v.lhs) != null && bool(v.rhs) != null) BoolValue(bool(v.lhs) != bool(v.rhs)) else v
        is InvertBool -> when (val inner = v.value) {
            is BoolValue -> BoolValue(!inner.value)
            is InvertBool -> inner.value
            else -> v
        }
        is IfElse -> when (bool(v.cond)) {
            true -> v.then
            false -> v.alt
            null -> v
        }

        is ConcatStr -> {
            val a = str(v.lhs)
            val b = str(v.rhs)
            when {
                a != null && b != null -> StringValue(a + b)
                // ("a" + x) + "b" stays, but (x + "a") + "b" is x + "ab"
                b != null && v.lhs is ConcatStr && str((v.lhs as ConcatStr).rhs) != null ->
                    ConcatStr((v.lhs as ConcatStr).lhs, StringValue(str((v.lhs as ConcatStr).rhs)!! + b))
                else -> v
            }
        }

        is IsNull -> when {
            v.checked == NullValue -> BoolValue(true)
            isLiteral(v.checked) -> BoolValue(false)
            else -> v
        }
        is Elvis -> when {
            v.value == NullValue -> v.alt
            isLiteral(v.value) -> v.value
            else -> v
        }
        is NotNull -> if (isLiteral(v.value) && v.value != NullValue) v.value else v

        is AccessRecordField -> {
            val list = v.accessed
            if (v.target.name == "List" && v.name == "size" && list is ListLiteral && list.list.all { isLiteral(it) }) IntValue(list.list.size)
            else v
        }
        is AccessListContainer -> {
            val list = v.container
            val index = int(v.key)
            if (list is ListLiteral && index != null && index in list.list.indices && list.list.all { isLiteral(it) }) list.list[index] else v
        }
        is InList -> {
            val values = literals(v.list)
            if (values != null && isLiteral(v.element)) BoolValue(kotlinValue(v.element) in values) else v
        }

        else -> v
    }
}

/**
 * Removes what nothing uses: functions (also external ones), attributes with all their equations, graphs and
 * parser rules. What the compiled file declares is used, as is what [keep] names; the declarations of the files it
 * imports are kept only if something that is kept uses them. So a module that imports a library keeps the part of
 * it that it needs.
 */
class DeadCodeElimination(private val keep: Set<String>) : OptimizationPhase() {
    override val name: String get() = "dead code elimination"

    override fun apply(module: LLIRModule): Pair<LLIRModule, Boolean> {
        val used = Uses(module)
        // the roots: the compiled file and what -keep names
        val main = module.main
        for (fn in module.functions) {
            val qualified = fn.receiver?.let { "$it.${fn.name}" }
            if (fn in main.functions || fn.name in keep || qualified in keep) used.function(fn)
        }
        main.attributes.forEach { used.attribute(it) }
        for (n in module.nodes) for (name in n.synthetics.keys + n.circulars.keys + n.collections.keys + n.inhEquations.keys) if (name in keep) used.attribute(name)
        module.inherited.keys.filter { it in keep }.forEach { used.attribute(it) }
        for (g in module.graphs) if (g.name in main.graphs || g.name in keep) used.graph(g.name)
        for (p in module.parsers) for (r in p.rules.keys) if (p.name in main.parsers || p.name in keep || "${p.name}.$r" in keep) used.rule(p.name, r)
        used.complete()

        var changed = false
        val functions = module.functions.filter { it in used.functions }
        if (functions.size != module.functions.size) changed = true
        for (n in module.nodes) {
            for (map in listOf(n.synthetics, n.circulars, n.collections, n.inhEquations)) {
                if (map.keys.removeIf { it !in used.attributes }) changed = true
            }
        }
        val inherited = LinkedHashMap(module.inherited.filterKeys { it in used.attributes })
        if (inherited.size != module.inherited.size) changed = true
        val graphs = module.graphs.filter { it.name in used.graphs }
        if (graphs.size != module.graphs.size) changed = true
        for (p in module.parsers) if (p.rules.keys.removeIf { (p.name to it) !in used.rules }) changed = true
        val parsers = module.parsers.filter { it.rules.isNotEmpty() }
        if (parsers.size != module.parsers.size) changed = true
        return module.copy(functions = functions, inherited = inherited, parsers = parsers, graphs = graphs) to changed
    }

    /** What is used: everything [function], [attribute], [graph] or [rule] is called for, and what that uses. */
    private class Uses(private val module: LLIRModule) {
        val functions = mutableSetOf<Function>()
        val attributes = mutableSetOf<String>()
        val graphs = mutableSetOf<String>()
        val rules = mutableSetOf<Pair<String, String>>()
        /** What is used but not yet scanned; a work list keeps the recursion flat. */
        private val work = ArrayDeque<() -> Unit>()

        fun function(fn: Function) { if (functions.add(fn)) work.addLast { scan(fn.body) } }

        fun attribute(name: String) {
            if (!attributes.add(name)) return
            work.addLast {
                for (node in module.nodes) {
                    node.synthetics[name]?.let { scan(it.body) }
                    node.circulars[name]?.let { scan(it.init); scan(it.body) }
                    node.collections[name]?.let { c -> scan(c.init); c.contributions.forEach { scan(it.target); scan(it.value) } }
                    node.inhEquations[name]?.let { eq -> eq.delegates.forEach { scan(it.key); scan(it.value); scan(it.child) } }
                }
                module.inherited[name]?.let { scan(it.init) }
            }
        }

        fun graph(name: String) {
            if (!graphs.add(name)) return
            work.addLast { module.graphs.firstOrNull { it.name == name }?.flows?.forEach { rule -> rule.body.forEach { scanFlow(it) } } }
        }

        fun rule(parser: String, name: String) {
            if (!rules.add(parser to name)) return
            work.addLast {
                val rule = module.parsers.firstOrNull { it.name == parser }?.rules?.get(name)
                for (alt in rule?.alternatives.orEmpty()) for (option in alt.options) {
                    scanGrammar(parser, option.body)
                    scan(option.producer)
                }
            }
        }

        fun complete() { while (work.isNotEmpty()) work.removeFirst()() }

        private fun scan(v: Value?) {
            v?.forEachValue { x ->
                when (x) {
                    is Call -> {
                        val owner = x.target.receiver
                        when {
                            owner is ParserType -> rule(owner.name, x.target.name)
                            owner is FlowGraphType -> graph(owner.name)
                            owner is ComplexType && owner.name == GRAPH -> {}
                            else -> function(x.target)
                        }
                    }
                    is AccessNodeAttr -> attribute(x.name)
                    is AccessParentInhAttr -> attribute(x.name)
                    is FlowGraphRef -> graph(x.graph.name)
                    else -> {}
                }
            }
        }

        private fun scanFlow(stmt: FlowStmt) {
            when (stmt) {
                is FlowEntry -> scan(stmt.target)
                is FlowConnect -> { scan(stmt.source); scan(stmt.target); scan(stmt.label) }
                is FlowVertex -> scan(stmt.label)
                is FlowSequence -> scan(stmt.items)
                is FlowIf -> { scan(stmt.cond); stmt.then.forEach { scanFlow(it) }; stmt.alt.forEach { scanFlow(it) } }
                is FlowFor -> { scan(stmt.iterable); stmt.body.forEach { scanFlow(it) } }
            }
        }

        private fun scanGrammar(parser: String, e: GExpr) {
            when (e) {
                is GPrimitive -> scan(e.argument)
                is GRepeat -> { scan(e.count); scanGrammar(parser, e.expr) }
                is GPredicate -> scanGrammar(parser, e.expr)
                is GChoice -> e.alternatives.forEach { scanGrammar(parser, it) }
                is GSequence -> e.items.forEach { scanGrammar(parser, it.expr) }
                is GNonterminal -> if (e.role == NonterminalRole.RULE) rule(parser, e.rule)
                else -> {}
            }
        }
    }
}
