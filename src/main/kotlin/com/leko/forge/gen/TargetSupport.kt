package com.leko.forge.gen

import com.leko.forge.llir.*
import com.leko.forge.llir.Function
import com.leko.forge.model.EDGE
import com.leko.forge.model.GRAPH
import com.leko.forge.model.SPAN
import com.leko.forge.model.VERTEX
import com.leko.forge.types.*

/**
 * What the targets other than Kotlin share. Kotlin maps Forge onto its own classes, interfaces and virtual
 * dispatch; the other targets resolve at generation time which equation of an attribute applies to which
 * concrete node, lower `match` to tests and bindings, and give every local variable a unique name.
 */
internal class TargetSupport(val module: LLIRModule) {

    val vertex: Node = module.nodes.first { it.name == VERTEX }

    /** The nodes declared by the program (not `Vertex`). */
    val nodes: List<Node> = module.nodes.filter { !it.isBuiltin }
    val concrete: List<Node> = nodes.filter { it.isConcrete }
    val interfaces: List<Node> = nodes.filter { it.isInterface }

    /** Names of syn and circular attributes, in declaration order. */
    val attributeNames: List<String> = module.nodes.flatMap { it.synthetics.keys + it.circulars.keys }.distinct()

    /** Names of collection attributes. */
    val collectionNames: List<String> = module.nodes.flatMap { it.collections.keys }.distinct()

    /** The types of attributes by name (a name has one type wherever it is declared). */
    val attributeTypes: Map<String, Type> = buildMap {
        for (n in module.nodes) {
            n.synthetics.values.forEach { putIfAbsent(it.name, it.type) }
            n.circulars.values.forEach { putIfAbsent(it.name, it.type) }
            n.collections.values.forEach { putIfAbsent(it.name, it.type) }
        }
        module.inherited.values.forEach { putIfAbsent(it.name, it.type) }
    }

    sealed interface Equation {
        class Syn(val attr: SynAttribute, val body: Value) : Equation
        class Circular(val attr: CircularAttribute) : Equation
        /** Declared without an equation (on an interface) and not defined for this node. */
        data object Missing : Equation
    }

    /**
     * The equation of attribute [name] for [node] (a concrete node, or `Vertex` for the vertices the runtime
     * creates): its own, else the one of its most specific supertype; `null` if the node does not have it.
     */
    fun equation(node: Node, name: String): Equation? {
        val owners = listOf(node) + node.ancestors().sortedByDescending { it.ancestors().size }
        for (owner in owners) {
            owner.circulars[name]?.let { return Equation.Circular(it) }
            owner.synthetics[name]?.let { syn -> return syn.body?.let { Equation.Syn(syn, it) } ?: Equation.Missing }
        }
        return null
    }

    /** The flows of a graph, most specific node first (the later flow first among equals). */
    fun flowRules(graph: FlowGraphDecl): List<FlowRule> = graph.flows.withIndex()
        .sortedWith(compareByDescending<IndexedValue<FlowRule>> { it.value.node.ancestors().size }.thenByDescending { it.index })
        .map { it.value }

    /** Supertypes of a node that a target declares, most specific first. */
    fun parentsOf(node: Node): List<Node> = node.parents.sortedByDescending { it.ancestors().size }

    // ---- types -----------------------------------------------------------------------------------

    val nodeNames: Set<String> = module.nodes.map { it.name }.toSet()
    val enumNames: Set<String> = module.enums.map { it.name }.toSet()
    val recordNames: Set<String> = module.records.map { it.name }.toSet()

    fun isNode(type: Type): Boolean = type is ComplexType && type.name in nodeNames
    fun nodeOf(type: Type): Node? = (type as? ComplexType)?.let { t -> module.nodes.firstOrNull { it.name == t.name } }

    /** The concrete nodes a value of node type [node] can be. */
    fun concreteOf(node: Node): List<Node> = concrete.filter { it.isSubtypeOf(node) }

    // ---- functions -------------------------------------------------------------------------------

    /** A name for a function that is unique without overloading: `f`, or `Recv_f` for methods. */
    fun functionName(fn: Function): String {
        val receiver = fn.receiver ?: return fn.name
        return typeKey(receiver) + "_" + fn.name
    }

    fun typeKey(type: Type): String = when (type) {
        IntType -> "int"
        FloatType -> "float"
        BoolType -> "bool"
        StrType -> "str"
        is ListType -> "List_" + typeKey(type.elem)
        is MapType -> "Map_" + typeKey(type.key) + "_" + typeKey(type.value)
        is OptionalType -> typeKey(type.elem) + "_opt"
        is ComplexType -> type.name
        is ParserType -> type.name
        else -> "any"
    }

    /** Builtin functions and methods, provided by every runtime. */
    fun isBuiltinCall(call: Call): Boolean {
        val owner = call.target.receiver
        return (owner == null && call.target.body == null && call.target.name in setOf("graphOf", "spanOf")) ||
            (owner is ComplexType && owner.name == GRAPH) || owner is FlowGraphType
    }

    fun isBuiltinRecord(name: String): Boolean = name == EDGE || name == GRAPH || name == SPAN

    // ---- match -----------------------------------------------------------------------------------

    /** A step of testing a pattern: a condition that must hold, or a variable bound to a value. */
    sealed interface Step {
        data class Test(val cond: String) : Step
        data class Bind(val name: String, val value: String) : Step
    }

    /** How a target tests and takes apart values, for [steps]. */
    interface Patterns {
        fun fresh(): String
        fun isItem(subject: String, item: EnumItem): String
        fun itemField(subject: String, item: EnumItem, field: String): String
        fun sizeIs(subject: String, size: Int): String
        fun notEmpty(subject: String): String
        fun element(subject: String, index: Int): String
        fun tail(subject: String): String
        /** The element [index] of a tuple. */
        fun tupleElement(subject: String, index: Int): String = element(subject, index)
        fun equalTo(subject: String, value: Value): String
        /** Binds a variable of the pattern; returns its name in the target. */
        fun bindVariable(variable: LocalRef): String
        /** A reference to a bound variable (some targets copy values on every use). */
        fun use(name: String): String = name
    }

    /** The tests and bindings that match [pattern] against the variable [subject]. */
    fun steps(pattern: MatchPattern, subject: String, p: Patterns): List<Step> = buildList {
        fun walk(pattern: MatchPattern, s: String) {
            when (pattern) {
                WildcardPattern -> {}
                is BindPattern -> add(Step.Bind(p.bindVariable(pattern.variable), p.use(s)))
                is ValuePattern -> add(Step.Test(p.equalTo(p.use(s), pattern.value)))
                is ConstructorPattern -> {
                    add(Step.Test(p.isItem(p.use(s), pattern.item)))
                    pattern.item.fields.keys.zip(pattern.args).forEach { (field, arg) ->
                        if (arg == WildcardPattern) return@forEach
                        val v = p.fresh()
                        add(Step.Bind(v, p.itemField(p.use(s), pattern.item, field)))
                        walk(arg, v)
                    }
                }
                is ListPattern -> {
                    add(Step.Test(p.sizeIs(p.use(s), pattern.elements.size)))
                    pattern.elements.forEachIndexed { i, element ->
                        if (element == WildcardPattern) return@forEachIndexed
                        val v = p.fresh()
                        add(Step.Bind(v, p.element(p.use(s), i)))
                        walk(element, v)
                    }
                }
                is TuplePattern -> pattern.elements.forEachIndexed { i, element ->
                    if (element == WildcardPattern) return@forEachIndexed
                    val v = p.fresh()
                    add(Step.Bind(v, p.tupleElement(p.use(s), i)))
                    walk(element, v)
                }
                is SplitPattern -> {
                    add(Step.Test(p.notEmpty(p.use(s))))
                    val head = p.fresh()
                    val tail = p.fresh()
                    add(Step.Bind(head, p.element(p.use(s), 0)))
                    add(Step.Bind(tail, p.tail(p.use(s))))
                    walk(pattern.head, head)
                    walk(pattern.tail, tail)
                }
            }
        }
        walk(pattern, subject)
    }

    // ---- lambdas ---------------------------------------------------------------------------------

    /**
     * The names of the variables and parameters a value refers to (also those it binds itself), and `this` if it
     * uses it: what a lambda may capture, for targets that copy what a closure captures.
     */
    fun referencedNames(v: Value): Set<String> = buildSet {
        v.forEachValue { x ->
            when (x) {
                is LocalRef -> add(x.name)
                is Parameter -> add(x.name)
                is This, is AccessParentInhAttr, is BoundCheck -> add("this")
                is Match -> x.cases.forEach { c -> patternNames(c.pattern, this) }
                else -> {}
            }
        }
    }

    private fun patternNames(p: MatchPattern, into: MutableSet<String>) {
        when (p) {
            is BindPattern -> into += p.variable.name
            is ConstructorPattern -> p.args.forEach { patternNames(it, into) }
            is ListPattern -> p.elements.forEach { patternNames(it, into) }
            is SplitPattern -> { patternNames(p.head, into); patternNames(p.tail, into) }
            is TuplePattern -> p.elements.forEach { patternNames(it, into) }
            is ValuePattern -> p.value.forEachValue { x -> if (x is LocalRef) into += x.name; if (x is Parameter) into += x.name }
            WildcardPattern -> {}
        }
    }

    // ---- grammars --------------------------------------------------------------------------------

    fun terminals(parser: ParserDecl): List<GTerminal> = collect(parser).filterIsInstance<GTerminal>().distinct()
    fun bitPatterns(parser: ParserDecl): List<GBits> = collect(parser).filterIsInstance<GBits>().distinct()

    private fun collect(parser: ParserDecl): List<GExpr> = buildList {
        fun walk(e: GExpr) {
            add(e)
            when (e) {
                is GRepeat -> walk(e.expr)
                is GPredicate -> walk(e.expr)
                is GChoice -> e.alternatives.forEach { walk(it) }
                is GSequence -> e.items.forEach { walk(it.expr) }
                else -> {}
            }
        }
        for (rule in parser.rules.values) for (alt in rule.alternatives) for (option in alt.options) walk(option.body)
    }

    /** How a parsing expression is named in error messages (`!x`). */
    fun describe(e: GExpr): String = when (e) {
        is GTerminal -> e.name ?: e.literal?.let { "'$it'" } ?: "/${e.regex}/"
        is GBits -> e.description
        is GPrimitive -> "@${e.name}"
        is GNonterminal -> e.rule
        is GRepeat -> describe(e.expr)
        is GPredicate -> describe(e.expr)
        is GChoice -> e.alternatives.joinToString(" or ") { describe(it) }
        is GSequence -> e.items.joinToString(" ") { describe(it.expr) }
    }

    /** How a terminal is shown in error messages. */
    fun display(t: GTerminal): String = t.name ?: if (t.literal != null) "'" + t.literal + "'" else "/" + t.regex + "/"

    /** The binding strength of the operand of an operator alternative, `null` for plain alternatives. */
    fun operandPrecedence(alt: GrammarAlternative): Int? = when (alt.operator) {
        null -> null
        OperatorKind.INFIX_LEFT -> alt.precedence + 1
        else -> alt.precedence
    }
}

/** Unique names for the local variables of the generated code: Forge names may shadow each other, targets' not. */
internal class Names(private val keywords: Set<String>, private val sanitize: (String) -> String = { it }) {
    private var counter = 0
    private val scopes = ArrayDeque<MutableMap<String, String>>().apply { addLast(mutableMapOf()) }

    /** A new variable for the Forge name [name], visible until the scope ends; returns its target name. */
    fun bind(name: String, expression: String? = null): String {
        val target = fresh(name)
        scopes.last()[name] = expression ?: target
        return target
    }

    /** Makes [name] stand for the target expression [expression] (e.g. `this`, a label cell). */
    fun alias(name: String, expression: String) {
        scopes.last()[name] = expression
    }

    fun fresh(hint: String = "v"): String {
        val base = sanitize(hint.trimStart('_').ifEmpty { "v" })
        return "${base}_${counter++}"
    }

    fun lookup(name: String): String =
        scopes.reversed().firstNotNullOfOrNull { it[name] } ?: throw IllegalStateException("unbound local '$name'")

    fun <T> scoped(block: () -> T): T {
        scopes.addLast(mutableMapOf())
        try {
            return block()
        } finally {
            scopes.removeLast()
        }
    }

    fun id(name: String): String = if (name in keywords) name + "_" else name
}

/** Loads a runtime that a target puts into every generated file. */
internal fun runtimeResource(name: String): String =
    TargetSupport::class.java.getResourceAsStream("/forge/runtime/$name")?.readBytes()?.toString(Charsets.UTF_8)
        ?: throw IllegalStateException("missing runtime resource $name")
