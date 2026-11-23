package com.leko.forge.attr

import com.leko.forge.model.*
import com.leko.forge.types.*

/**
 * Built-in declarations for graphs:
 *
 * - `Vertex`: interface node implemented by every node, and by the synthetic and exit vertices of flows.
 *   Attributes can be declared on it, e.g. `syn Vertex.uses: List<str> = []`
 * - `Edge(from: Vertex, to: Vertex, label: str?)`: a record
 * - `Graph`: a directed graph (`entry`, `vertices`, `edges`, `root`, `succ(v)`, `pred(v)`, `reachable()`, ...)
 * - `graphOf(entry: Vertex, edges: List<Edge>): Graph` builds a graph from edges, e.g. a call graph
 * - `Span(source, line, column, offset, length, text)` and `spanOf(node): Span?`: where a parser read a node
 *
 * The builtin functions (`str.upper()`, `fail(message)`, ...) are `ext fun`s of the standard library, std.forge.
 */
object Builtins {

    fun enter(symTab: SymTab, typeMap: TypeMap) {
        val vertexType = ComplexType(VERTEX)
        val vertex = InterfaceNodeSymbol(VERTEX, mutableListOf(), builtinSpan)
        symTab[vertex.type] = vertex

        val edge = RecordSymbol(
            EDGE,
            listOf(
                field("from", vertexType),
                field("to", vertexType),
                field("label", OptionalType(StrType)),
            ),
            builtinSpan,
        )
        symTab[edge.type] = edge
        val edgeType = edge.type

        val graph = RecordSymbol(
            GRAPH,
            listOf(
                field("entry", vertexType),
                field("vertices", ListType(vertexType)),
                field("edges", ListType(edgeType)),
                field("root", OptionalType(vertexType)),
            ),
            builtinSpan,
            opaque = true,
        )
        symTab[graph.type] = graph

        fun method(name: String, params: List<Pair<String, Type>>, returnType: Type) =
            FunctionSymbol(graph, name, params.map { (n, t) -> VarSymbol(n, t, null, VarSymbol.VarKind.PARAMETER) }, returnType, builtinSpan)

        typeMap.methods[graph] = listOf(
            method("succ", listOf("vertex" to vertexType), ListType(vertexType)),
            method("pred", listOf("vertex" to vertexType), ListType(vertexType)),
            method("outEdges", listOf("vertex" to vertexType), ListType(edgeType)),
            method("inEdges", listOf("vertex" to vertexType), ListType(edgeType)),
            method("contains", listOf("vertex" to vertexType), BoolType),
            method("indexOf", listOf("vertex" to vertexType), IntType),
            method("exit", listOf("port" to StrType), OptionalType(vertexType)),
            method("reachable", emptyList(), ListType(vertexType)),
            method("postorder", emptyList(), ListType(vertexType)),
            method("reversePostorder", emptyList(), ListType(vertexType)),
            method("toDot", emptyList(), StrType),
        )

        val span = RecordSymbol(
            SPAN,
            listOf(
                field("source", OptionalType(StrType)),
                field("line", IntType),
                field("column", IntType),
                field("offset", IntType),
                field("length", IntType),
                field("text", StrType),
            ),
            builtinSpan,
        )
        symTab[span.type] = span
        symTab.functions["spanOf"] = FunctionSymbol(
            null,
            "spanOf",
            listOf(VarSymbol("node", vertexType, null, VarSymbol.VarKind.PARAMETER)),
            OptionalType(span.type),
            builtinSpan,
        )

        symTab.functions["graphOf"] = FunctionSymbol(
            null,
            "graphOf",
            listOf(
                VarSymbol("entry", vertexType, null, VarSymbol.VarKind.PARAMETER),
                VarSymbol("edges", ListType(edgeType), null, VarSymbol.VarKind.PARAMETER),
            ),
            graph.type,
            builtinSpan,
        )
    }

    private fun field(name: String, type: Type) = VarSymbol(name, type, null, VarSymbol.VarKind.RECORD_FIELD)

    fun isBuiltin(sym: Symbol): Boolean = sym.span === builtinSpan
}
