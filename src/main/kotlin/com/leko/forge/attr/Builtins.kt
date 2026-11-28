package com.leko.forge.attr

import com.leko.forge.model.*
import com.leko.forge.types.*

/**
 * The builtin types, which the runtimes of the targets define:
 *
 * - `Vertex`: interface node implemented by every node, and by the synthetic and exit vertices of flows.
 *   Attributes can be declared on it, e.g. `syn Vertex.uses: List<str> = []`
 * - `Edge(from: Vertex, to: Vertex, label: str?)`: a record
 * - `Graph`: a directed graph with the fields `entry`, `vertices`, `edges` and `root`
 * - `Span(source, line, column, offset, length, text)`: where a parser read a node
 *
 * Every function is declared in Forge: those of these types (`Graph.succ(v)`, `graphOf(entry, edges)`,
 * `spanOf(node)`) and the other builtin functions (`str.upper()`, `fail(message)`, ...) are `ext fun`s of the
 * standard library, std.forge.
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
    }

    private fun field(name: String, type: Type) = VarSymbol(name, type, null, VarSymbol.VarKind.RECORD_FIELD)

    fun isBuiltin(sym: Symbol): Boolean = sym.span === builtinSpan
}
