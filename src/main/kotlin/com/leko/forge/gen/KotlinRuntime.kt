package com.leko.forge.gen

/**
 * Support code that the Kotlin target puts into every generated file: attribute caches, the fixpoint
 * computation of circular attributes, graphs and the construction of graphs from flows.
 *
 * Every type is fully qualified, because the generated file may declare nodes called `Int`, `String`, ...
 * The `Vertex` interface, `__ExitVertex` and `__SyntheticVertex` are generated with the nodes, since
 * attributes can be declared on `Vertex`.
 */
internal val KOTLIN_RUNTIME: String = """
// ==== Forge runtime ==========================================================================

class __Box<T>(val value: T)

/** The fixpoint computation currently running, shared by all circular attributes. */
object __CircularEval {
    class Cycle {
        var iteration: kotlin.Int = 0
        var changed: kotlin.Boolean = false
        val attributes: kotlin.collections.MutableList<__Circular<*>> = kotlin.collections.ArrayList()
        fun involve(attribute: __Circular<*>) {
            if (attributes.none { it === attribute }) attributes.add(attribute)
        }
    }

    var cycle: Cycle? = null

    /** Whether a fixpoint is being computed. Other attributes must not cache values then. */
    fun inCycle(): kotlin.Boolean = cycle != null
}

/** Values of one syn, inh or coll attribute, per node. Nothing is cached while a fixpoint is computed. */
class __Cache<T>(private val attribute: kotlin.String) {
    private val values = java.util.WeakHashMap<kotlin.Any, __Box<T>>()
    private val inProgress = java.util.IdentityHashMap<kotlin.Any, kotlin.Unit>()

    fun get(node: kotlin.Any, compute: () -> T): T {
        values[node]?.let { return it.value }
        if (inProgress.containsKey(node)) {
            kotlin.error("attribute '" + attribute + "' of " + node + " depends on itself; declare it as circular")
        }
        inProgress[node] = kotlin.Unit
        try {
            val value = compute()
            if (!__CircularEval.inCycle()) values[node] = __Box(value)
            return value
        } finally {
            inProgress.remove(node)
        }
    }
}

/**
 * A circular attribute. Its value is computed as a fixpoint: every value starts at the initial value and
 * all equations involved are evaluated again until nothing changes (values are compared with `==`).
 */
class __Circular<T> {
    private val final = java.util.WeakHashMap<kotlin.Any, __Box<T>>()
    private var approx = java.util.IdentityHashMap<kotlin.Any, __Box<T>>()
    private var visited = java.util.IdentityHashMap<kotlin.Any, kotlin.Int>()

    fun get(node: kotlin.Any, initial: T, body: () -> T): T {
        final[node]?.let { return it.value }

        val cycle = __CircularEval.cycle
        if (cycle != null) {
            cycle.involve(this)
            // already evaluated in this round: use the current approximation
            if (visited[node] == cycle.iteration) return approxOf(node, initial)
            return step(node, initial, body, cycle)
        }

        // start a new fixpoint computation
        val c = __CircularEval.Cycle()
        __CircularEval.cycle = c
        try {
            c.involve(this)
            do {
                c.changed = false
                c.iteration++
                step(node, initial, body, c)
            } while (c.changed)
            // nothing changed in the last round: every value computed in it is final
            for (attribute in c.attributes) attribute.finish(c.iteration)
            return final[node]!!.value
        } finally {
            __CircularEval.cycle = null
            for (attribute in c.attributes) attribute.reset()
        }
    }

    private fun approxOf(node: kotlin.Any, initial: T): T {
        val box = approx[node]
        return if (box != null) box.value else initial
    }

    private fun step(node: kotlin.Any, initial: T, body: () -> T, cycle: __CircularEval.Cycle): T {
        visited[node] = cycle.iteration
        val old = approxOf(node, initial)
        val new = body()
        if (new != old || !approx.containsKey(node)) {
            if (new != old) cycle.changed = true
            approx[node] = __Box(new)
        }
        return new
    }

    private fun finish(lastIteration: kotlin.Int) {
        for ((node, iteration) in visited) {
            if (iteration == lastIteration) final[node] = approx[node]!!
        }
    }

    private fun reset() {
        approx = java.util.IdentityHashMap()
        visited = java.util.IdentityHashMap()
    }
}

/** A directed edge. */
class Edge(val from: Vertex, val to: Vertex, val label: kotlin.String? = null) {
    override fun toString(): kotlin.String = if (label == null) from.toString() + " -> " + to else from.toString() + " -[" + label + "]-> " + to
}

/** Builds a graph from edges, e.g. a call graph. The vertices are [entry] and the endpoints of [edges]. */
fun graphOf(entry: Vertex, edges: kotlin.collections.List<Edge>): Graph =
    Graph(null, entry, kotlin.collections.listOf(entry) + edges.flatMap { kotlin.collections.listOf(it.from, it.to) }, edges, kotlin.collections.emptyMap())

/** A directed graph. Vertices are compared by identity. */
class Graph(
    val root: Vertex?,
    val entry: Vertex,
    vertices: kotlin.collections.List<Vertex>,
    val edges: kotlin.collections.List<Edge>,
    private val exitVertices: kotlin.collections.Map<__Port, Vertex>,
) {
    private val index = java.util.IdentityHashMap<Vertex, kotlin.Int>()

    /** All vertices, without duplicates, in the order they were found. */
    val vertices: kotlin.collections.List<Vertex>

    private val outgoing: kotlin.collections.List<kotlin.collections.MutableList<Edge>>
    private val incoming: kotlin.collections.List<kotlin.collections.MutableList<Edge>>

    init {
        val distinct = kotlin.collections.ArrayList<Vertex>()
        fun add(v: Vertex) { if (!index.containsKey(v)) { index[v] = distinct.size; distinct.add(v) } }
        for (v in vertices) add(v)
        for (e in edges) { add(e.from); add(e.to) }
        this.vertices = distinct
        outgoing = kotlin.collections.List(distinct.size) { kotlin.collections.ArrayList<Edge>() }
        incoming = kotlin.collections.List(distinct.size) { kotlin.collections.ArrayList<Edge>() }
        for (e in edges) {
            outgoing[index[e.from]!!].add(e)
            incoming[index[e.to]!!].add(e)
        }
    }

    fun contains(vertex: Vertex): kotlin.Boolean = index.containsKey(vertex)

    /** The index of [vertex] in [vertices], or -1. */
    fun indexOf(vertex: Vertex): kotlin.Int = index[vertex] ?: -1

    fun outEdges(vertex: Vertex): kotlin.collections.List<Edge> = index[vertex]?.let { outgoing[it] } ?: kotlin.collections.emptyList()
    fun inEdges(vertex: Vertex): kotlin.collections.List<Edge> = index[vertex]?.let { incoming[it] } ?: kotlin.collections.emptyList()

    fun succ(vertex: Vertex): kotlin.collections.List<Vertex> = outEdges(vertex).map { it.to }
    fun pred(vertex: Vertex): kotlin.collections.List<Vertex> = inEdges(vertex).map { it.from }

    /** The vertex that the unbound port [port] of the root leads to, e.g. the end of a function for "next". */
    fun exit(port: kotlin.String): Vertex? = exitVertices.entries.firstOrNull { it.key.name == port }?.value

    /** Vertices reachable from the entry, in depth first preorder. */
    fun reachable(): kotlin.collections.List<Vertex> {
        val seen = kotlin.BooleanArray(vertices.size)
        val result = kotlin.collections.ArrayList<Vertex>()
        val work = kotlin.collections.ArrayDeque<Vertex>()
        work.addLast(entry)
        while (work.isNotEmpty()) {
            val v = work.removeLast()
            val i = indexOf(v)
            if (i < 0 || seen[i]) continue
            seen[i] = true
            result.add(v)
            succ(v).asReversed().forEach { work.addLast(it) }
        }
        return result
    }

    /** Vertices reachable from the entry, in depth first postorder. */
    fun postorder(): kotlin.collections.List<Vertex> {
        val seen = kotlin.BooleanArray(vertices.size)
        val result = kotlin.collections.ArrayList<Vertex>()
        val stack = kotlin.collections.ArrayDeque<kotlin.Pair<Vertex, kotlin.collections.Iterator<Vertex>>>()
        fun push(v: Vertex) {
            val i = indexOf(v)
            if (i < 0 || seen[i]) return
            seen[i] = true
            stack.addLast(kotlin.Pair(v, succ(v).iterator()))
        }
        push(entry)
        while (stack.isNotEmpty()) {
            val (v, children) = stack[stack.size - 1]
            if (children.hasNext()) push(children.next())
            else {
                stack.removeLast()
                result.add(v)
            }
        }
        return result
    }

    /** Every vertex comes before its successors, except along back edges. */
    fun reversePostorder(): kotlin.collections.List<Vertex> = postorder().asReversed()

    /** The graph in Graphviz DOT format. */
    fun toDot(): kotlin.String {
        val sb = java.lang.StringBuilder()
        sb.append("digraph G {\n")
        vertices.forEachIndexed { i, v -> sb.append("  n").append(i).append(" [label=\"").append(__escape(v.toString())).append("\"];\n") }
        for (e in edges) {
            sb.append("  n").append(indexOf(e.from)).append(" -> n").append(indexOf(e.to))
            if (e.label != null) sb.append(" [label=\"").append(__escape(e.label)).append("\"]")
            sb.append(";\n")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun __escape(s: kotlin.String): kotlin.String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    override fun toString(): kotlin.String = "Graph(" + vertices.size + " vertices, " + edges.size + " edges)"
}

/** A named exit of a flow. Inherited ports are looked up through the enclosing flows when the parent does not bind them. */
class __Port(val name: kotlin.String, val inherited: kotlin.Boolean) {
    override fun toString(): kotlin.String = name

    companion object {
        val next: __Port = __Port("next", false)
    }
}

/** The port of a child, e.g. `onTrue(cond)`. */
class __PortRef(val node: kotlin.Any, val port: __Port) {
    override fun toString(): kotlin.String = port.toString() + "(" + node + ")"
}

/** A graph definition: one graph per root node, built from the flows of the nodes below it. */
class __FlowGraph<R : kotlin.Any>(
    private val isRoot: (kotlin.Any) -> kotlin.Boolean,
    private val rule: (__FlowScope, kotlin.Any) -> kotlin.Boolean,
) {
    private val graphs = java.util.WeakHashMap<kotlin.Any, Graph>()
    private val owners = java.util.WeakHashMap<kotlin.Any, Graph>()

    /** The graph of [root], built on first access. */
    fun get(root: R): Graph {
        graphs[root]?.let { return it }
        val graph = __FlowBuild(root, rule).build()
        graphs[root] = graph
        for (v in graph.vertices) owners[v] = graph
        return graph
    }

    /** The graph [vertex] is part of, or the graph of its nearest enclosing root. */
    @Suppress("UNCHECKED_CAST")
    fun of(vertex: Vertex): Graph? {
        owners[vertex]?.let { return it }
        var current: kotlin.Any? = vertex
        while (current != null) {
            if (isRoot(current)) return get(current as R)
            current = (current as? __Node)?.__parent
        }
        return null
    }

    fun succ(vertex: Vertex): kotlin.collections.List<Vertex> = of(vertex)?.succ(vertex) ?: kotlin.collections.emptyList()
    fun pred(vertex: Vertex): kotlin.collections.List<Vertex> = of(vertex)?.pred(vertex) ?: kotlin.collections.emptyList()
}

sealed class __FlowTarget {
    class AVertex(val vertex: kotlin.Any) : __FlowTarget()
    class Child(val node: kotlin.Any) : __FlowTarget()
    class OwnPort(val node: kotlin.Any, val port: __Port) : __FlowTarget()
}

class __EdgeSpec(val from: kotlin.Any, val to: __FlowTarget, val label: kotlin.String?)

/**
 * Where a flow sends control. The node itself and the vertices declared in the flow are vertices of the
 * graph; every other node is a child, standing for the child's own flow.
 */
class __FlowScope(val node: kotlin.Any, private val build: __FlowBuild) {
    var entrySpec: __FlowTarget? = null
    private val ownVertices = java.util.IdentityHashMap<kotlin.Any, kotlin.Unit>()

    fun entry(target: kotlin.Any) {
        kotlin.check(entrySpec == null) { "the flow of " + node + " already has an entry" }
        entrySpec = toTarget(target)
    }

    fun connect(source: kotlin.Any, target: kotlin.Any, label: kotlin.String?) {
        val to = toTarget(target)
        when {
            source is __Port -> kotlin.error("a port of the node itself can not be connected; only ports of children: " + source + "(" + node + ")")
            source is __PortRef -> {
                kotlin.check(label == null) { "only edges from vertices can be labeled: " + source }
                build.reference(source.node, node)
                build.bind(source.node, source.port, to)
            }
            isVertex(source) -> {
                build.addVertex(source)
                build.edges.add(__EdgeSpec(source, to, label))
            }
            else -> {
                kotlin.check(label == null) { "only edges from vertices can be labeled: " + source }
                build.reference(source, node)
                build.bind(source, __Port.next, to)
            }
        }
    }

    fun <V : Vertex> vertex(vertex: V): V {
        ownVertices[vertex] = kotlin.Unit
        build.addVertex(vertex)
        return vertex
    }

    fun bound(port: __Port): kotlin.Boolean = build.isBound(node, port)

    fun sequence(items: kotlin.collections.List<kotlin.Any>) {
        if (items.isEmpty()) {
            entry(__Port.next)
            return
        }
        entry(items[0])
        for (i in 0 until items.size - 1) connect(items[i], items[i + 1], null)
        connect(items[items.size - 1], __Port.next, null)
    }

    private fun isVertex(x: kotlin.Any): kotlin.Boolean = x === node || ownVertices.containsKey(x)

    private fun toTarget(x: kotlin.Any): __FlowTarget = when {
        x is __Port -> __FlowTarget.OwnPort(node, x)
        x is __PortRef -> kotlin.error("a port of a child can not be a target: " + x + ". Connect it instead: " + x + " -> ...")
        isVertex(x) -> {
            build.addVertex(x)
            __FlowTarget.AVertex(x)
        }
        else -> {
            build.reference(x, node)
            __FlowTarget.Child(x)
        }
    }
}

/** Runs the flows of all nodes reachable from [root] and resolves them into a [Graph]. */
class __FlowBuild(private val root: kotlin.Any, private val rule: (__FlowScope, kotlin.Any) -> kotlin.Boolean) {
    private val scopes = java.util.IdentityHashMap<kotlin.Any, __FlowScope>()
    private val flowParent = java.util.IdentityHashMap<kotlin.Any, kotlin.Any>()
    private val bindings = java.util.IdentityHashMap<kotlin.Any, kotlin.collections.MutableMap<__Port, __FlowTarget>>()
    private val queue = kotlin.collections.ArrayDeque<kotlin.Any>()
    private val vertices = kotlin.collections.ArrayList<kotlin.Any>()
    private val vertexSet = java.util.IdentityHashMap<kotlin.Any, kotlin.Unit>()
    private val exits = kotlin.collections.LinkedHashMap<__Port, Vertex>()
    val edges: kotlin.collections.MutableList<__EdgeSpec> = kotlin.collections.ArrayList()

    fun build(): Graph {
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (scopes.containsKey(node)) continue
            val scope = __FlowScope(node, this)
            scopes[node] = scope
            if (!rule(scope, node)) {
                kotlin.error("no flow defined for " + node::class.simpleName + (flowParent[node]?.let { ", used in the flow of " + it::class.simpleName } ?: ""))
            }
        }
        val entry = resolve(__FlowTarget.Child(root))
        val resolvedEdges = edges.map { Edge(it.from as Vertex, resolve(it.to), it.label) }
        return Graph(root as? Vertex, entry, kotlin.collections.listOf(entry) + vertices.map { it as Vertex }, resolvedEdges, exits)
    }

    fun reference(child: kotlin.Any, by: kotlin.Any) {
        if (child === root || flowParent.containsKey(child)) return
        flowParent[child] = by
        queue.addLast(child)
    }

    fun bind(child: kotlin.Any, port: __Port, target: __FlowTarget) {
        val ports = bindings.getOrPut(child) { kotlin.collections.LinkedHashMap() }
        kotlin.check(port !in ports) { "port " + port + " of " + child + " is connected twice" }
        ports[port] = target
    }

    fun addVertex(v: kotlin.Any) {
        if (!vertexSet.containsKey(v)) {
            vertexSet[v] = kotlin.Unit
            vertices.add(v)
        }
    }

    fun isBound(node: kotlin.Any, port: __Port): kotlin.Boolean {
        if (bindings[node]?.containsKey(port) == true) return true
        if (!port.inherited) return false
        return flowParent[node]?.let { isBound(it, port) } ?: false
    }

    private val resolved = java.util.IdentityHashMap<__FlowTarget, Vertex>()
    private val resolving = java.util.IdentityHashMap<__FlowTarget, kotlin.Unit>()

    private fun resolve(target: __FlowTarget): Vertex {
        resolved[target]?.let { return it }
        kotlin.check(!resolving.containsKey(target)) { "flow cycle without a vertex at " + describe(target) }
        resolving[target] = kotlin.Unit
        try {
            val vertex = when (target) {
                is __FlowTarget.AVertex -> target.vertex as Vertex
                is __FlowTarget.Child -> {
                    val scope = scopes[target.node] ?: kotlin.error("no flow for " + target.node)
                    resolve(scope.entrySpec ?: kotlin.error("the flow of " + target.node + " has no entry"))
                }
                is __FlowTarget.OwnPort -> resolvePort(target.node, target.port)
            }
            resolved[target] = vertex
            return vertex
        } finally {
            resolving.remove(target)
        }
    }

    private fun resolvePort(node: kotlin.Any, port: __Port): Vertex {
        bindings[node]?.get(port)?.let { return resolve(it) }
        if (node === root) return exits.getOrPut(port) { __ExitVertex(port).also { addVertex(it) } }
        val parent = flowParent[node]!!
        if (port.inherited) return resolvePort(parent, port)
        kotlin.error("port " + port + " of " + node + " is not connected; connect it in the flow of " + parent)
    }

    private fun describe(target: __FlowTarget): kotlin.String = when (target) {
        is __FlowTarget.AVertex -> target.vertex.toString()
        is __FlowTarget.Child -> "entry of " + target.node
        is __FlowTarget.OwnPort -> target.port.toString() + "(" + target.node + ")"
    }
}

/** Where a parser read a node from: [offset] and [length] in characters (bytes for binary input). */
data class Span(
    val source: kotlin.String?,
    val line: kotlin.Int,
    val column: kotlin.Int,
    val offset: kotlin.Int,
    val length: kotlin.Int,
    val text: kotlin.String,
) {
    override fun toString(): kotlin.String = (if (source != null) source + ":" else "") + line + ":" + column
}

/** Where [node] was parsed from, or `null` if no parser created it. */
fun spanOf(node: Vertex): Span? = (node as? __Node)?.__span

/** The nodes of the tree below [node] (including it), parents before children. */
fun __preorder(node: __Node): kotlin.collections.List<__Node> {
    val result = kotlin.collections.ArrayList<__Node>()
    val work = kotlin.collections.ArrayDeque<__Node>()
    work.addLast(node)
    while (work.isNotEmpty()) {
        val n = work.removeLast()
        result.add(n)
        n.__children.asReversed().forEach { work.addLast(it) }
    }
    return result
}

// ---- builtins ---------------------------------------------------------------------------------

/** A position for a list of [size] elements: below 0 counts from the end. */
fun __position(i: kotlin.Int, size: kotlin.Int): kotlin.Int {
    val k = if (i < 0) i + size else i
    if (k < 0 || k >= size) throw kotlin.IndexOutOfBoundsException("index " + i + " out of bounds for length " + size)
    return k
}

/** A bound of a slice of [size] elements: below 0 counts from the end, clamped to 0..size. */
fun __bound(b: kotlin.Int?, size: kotlin.Int, default: kotlin.Int): kotlin.Int {
    val k = if (b == null) default else if (b < 0) b + size else b
    return if (k < 0) 0 else if (k > size) size else k
}

fun <T> __index(xs: kotlin.collections.List<T>, i: kotlin.Int): T = xs[__position(i, xs.size)]

fun <T> __slice(xs: kotlin.collections.List<T>, lo: kotlin.Int?, hi: kotlin.Int?): kotlin.collections.List<T> {
    val a = __bound(lo, xs.size, 0)
    val b = __bound(hi, xs.size, xs.size)
    return if (a >= b) kotlin.collections.emptyList() else xs.subList(a, b).toList()
}

fun __slice(s: kotlin.String, lo: kotlin.Int?, hi: kotlin.Int?): kotlin.String {
    val a = __bound(lo, s.length, 0)
    val b = __bound(hi, s.length, s.length)
    return if (a >= b) "" else s.substring(a, b)
}

fun __charAt(s: kotlin.String, i: kotlin.Int): kotlin.String = s[__position(i, s.length)].toString()

/** [f] of each element; Kotlin's `map` may be hidden by a `map` of the program. */
inline fun <T, R> __mapAll(xs: kotlin.collections.Iterable<T>, f: (T) -> R): kotlin.collections.List<R> {
    val result = kotlin.collections.ArrayList<R>()
    for (x in xs) result.add(f(x))
    return result
}

fun __range(a: kotlin.Int, b: kotlin.Int): kotlin.collections.List<kotlin.Int> =
    if (b <= a) kotlin.collections.emptyList() else (a until b).toList()

/** The whole text as an int: an optional sign and digits. */
fun __toInt(s: kotlin.String): kotlin.Int {
    if (!s.matches(kotlin.text.Regex("[+-]?[0-9]+"))) kotlin.error("not an int: \"" + s + "\"")
    return s.toIntOrNull() ?: kotlin.error("not an int: \"" + s + "\"")
}

/** The whole text as a float: digits with an optional point and exponent. */
fun __toLong(s: kotlin.String): kotlin.Long {
    if (!s.matches(kotlin.text.Regex("[+-]?[0-9]+"))) kotlin.error("not a long: \"" + s + "\"")
    return s.toLongOrNull() ?: kotlin.error("not a long: \"" + s + "\"")
}

fun __toFloat(s: kotlin.String): kotlin.Float {
    if (!s.matches(kotlin.text.Regex("[+-]?([0-9]+[.]?[0-9]*|[.][0-9]+)([eE][+-]?[0-9]+)?"))) kotlin.error("not a float: \"" + s + "\"")
    return s.toFloat()
}

fun __split(s: kotlin.String, separator: kotlin.String): kotlin.collections.List<kotlin.String> {
    if (separator.isEmpty()) kotlin.error("the separator of split is empty")
    return s.split(separator)
}

fun __replace(s: kotlin.String, old: kotlin.String, new: kotlin.String): kotlin.String {
    if (old.isEmpty()) kotlin.error("the text to replace is empty")
    return s.replace(old, new)
}

fun __code(s: kotlin.String, i: kotlin.Int): kotlin.Int = s[__position(i, s.length)].code

fun __char(code: kotlin.Int): kotlin.String = kotlin.text.String(java.lang.Character.toChars(code))

fun __utf8(s: kotlin.String): kotlin.collections.List<kotlin.Int> = s.encodeToByteArray().map { it.toInt() and 0xFF }

/** \n, \t, \r and \0 become their characters, a backslash before any other character is dropped. */
fun __unescape(s: kotlin.String): kotlin.String {
    val sb = java.lang.StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            i++
            sb.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '0' -> '\u0000'; else -> s[i] })
        } else {
            sb.append(c)
        }
        i++
    }
    return sb.toString()
}

// ==== end of Forge runtime ===================================================================
"""
