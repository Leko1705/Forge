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

        symTab.functions["char"] = char
        symTab.functions["fail"] = fail
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

    /**
     * The builtin methods of the values of [type] (their runtimes implement them in every target):
     *
     * - `int.str()`, `float.str()`, `bool.str()`: as text
     * - `str.toInt()`, `str.toFloat()` (fail unless the whole text is a number), `str.indexOf(s)` (-1 if absent),
     *   `startsWith(s)`, `endsWith(s)`, `replace(a, b)`, `upper()`, `lower()`, `trim()`, `split(separator)`,
     *   `code(i)` (the code of a character), `utf8()` (the bytes), `unescape()` (`\n`, `\t`, `\r`, `\0`,
     *   and `\x` for any other character x)
     * - `List<T>.indexOf(x)`, `reversed()`, `toSet()`, `List<str>.join(separator)`
     * - `Map<K, V>.keys()`, `values()` (lists, in the order of the map), `remove(key)`
     * - `Set<T>.toList()`, `remove(x)`
     */
    fun method(owner: Symbol, type: Type, name: String): FunctionSymbol? {
        val (key, signature) = when (type) {
            IntType, FloatType, BoolType -> "$type.$name" to if (name == "str") emptyList<Type>() to StrType else null
            StrType -> "str.$name" to when (name) {
                "toInt" -> emptyList<Type>() to IntType
                "toFloat" -> emptyList<Type>() to FloatType
                "indexOf" -> listOf(StrType) to IntType
                "startsWith", "endsWith" -> listOf(StrType) to BoolType
                "replace" -> listOf(StrType, StrType) to StrType
                "upper", "lower", "trim", "unescape" -> emptyList<Type>() to StrType
                "split" -> listOf(StrType) to ListType(StrType)
                "code" -> listOf(IntType) to IntType
                "utf8" -> emptyList<Type>() to ListType(IntType)
                else -> null
            }
            is ListType -> "List.$name" to when (name) {
                "indexOf" -> listOf(type.elem) to IntType
                "reversed" -> emptyList<Type>() to type
                "toSet" -> emptyList<Type>() to SetType(type.elem)
                "join" -> if (type.elem == StrType) listOf(StrType) to StrType else null
                else -> null
            }
            is MapType -> "Map.$name" to when (name) {
                "keys" -> emptyList<Type>() to ListType(type.key)
                "values" -> emptyList<Type>() to ListType(type.value)
                "remove" -> listOf(type.key) to type
                else -> null
            }
            is SetType -> "Set.$name" to when (name) {
                "toList" -> emptyList<Type>() to ListType(type.elem)
                "remove" -> listOf(type.elem) to type
                else -> null
            }
            else -> return null
        }
        val (params, result) = signature ?: return null
        return FunctionSymbol(
            owner, name,
            params.mapIndexed { i, t -> VarSymbol("p$i", t, null, VarSymbol.VarKind.PARAMETER) },
            result, builtinSpan, builtin = key,
        )
    }

    /** `fail(message)`: stops with an error; it has type Nothing, so it fits wherever a value is expected. */
    private val fail = FunctionSymbol(
        null, "fail", listOf(VarSymbol("message", StrType, null, VarSymbol.VarKind.PARAMETER)), NothingType, builtinSpan, builtin = "fail",
    )

    /** `char(code)`: the character with a code, as a str. */
    private val char = FunctionSymbol(
        null, "char", listOf(VarSymbol("code", IntType, null, VarSymbol.VarKind.PARAMETER)), StrType, builtinSpan, builtin = "char",
    )

    fun isBuiltin(sym: Symbol): Boolean = sym.span === builtinSpan
}
