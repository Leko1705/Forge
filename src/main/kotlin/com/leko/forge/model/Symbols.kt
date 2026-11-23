package com.leko.forge.model

import com.leko.forge.types.*

enum class Kind {
    VAL,
    TYP,
    MTH,

}

sealed interface Symbol {
    val name: String
    val type: Type
    val kind: Kind
    val span: SourceSpan?
}

/**
 * `const name: T = value`: a value known when Forge runs, inlined wherever it is used. The type is the declared
 * one, or the one of the value, which is attributed on first use (constants may be used before their definition).
 */
class ConstSymbol(override val name: String, val declared: Type?, override val span: SourceSpan?) : Symbol {
    enum class State { NEW, IN_PROGRESS, DONE }

    var state: State = State.NEW
    var inferred: Type? = null
    override val type: Type get() = declared ?: inferred ?: ErrorType
    override val kind: Kind get() = Kind.VAL
}

sealed interface CallableSymbol : Symbol {
    val params: List<VarSymbol>
    val returnType: Type
}

private fun primitive(name: String, type: Type, fields: (MutableList<VarSymbol>.() -> Unit)? = null): RecordSymbol =
    RecordSymbol(
        name = name,
        fields = if (fields != null) {
        val f = mutableListOf<VarSymbol>()
        f.fields()
        f
    } else {
        emptyList()
    },
        span = null,
        type = type
    )

val nullSymbol = primitive("null", NullType)

val intSymbol = primitive("int", IntType)

val floatSymbol = primitive("float", FloatType)

val boolSymbol = primitive("bool", BoolType)

val strSymbol = primitive("str", StrType) {
    add(VarSymbol("size", IntType, null, VarSymbol.VarKind.RECORD_FIELD))
}

fun setSymbol(setType: SetType) = primitive("Set", setType) {
    add(VarSymbol("size", IntType, null, VarSymbol.VarKind.RECORD_FIELD))
}

fun listSymbol(listType: ListType) = primitive("List", listType) {
    add(VarSymbol("size", IntType, null, VarSymbol.VarKind.RECORD_FIELD))
}

fun mapSymbol(mapType: MapType) = primitive("Map", mapType) {
    add(VarSymbol("size", IntType, null, VarSymbol.VarKind.RECORD_FIELD))
}

class VarSymbol(
    override val name: String,
    override val type: Type,
    override val span: SourceSpan?,
    val varKind: VarKind = VarKind.VARIABLE
) : Symbol {
    override val kind: Kind = Kind.VAL

    enum class VarKind {
        VARIABLE,
        PARAMETER,
        NODE_FIELD,
        RECORD_FIELD,

        PARENT_INH,
    }

    override fun toString(): String {
        return "VarSymbol(name='$name', type=$type, span=$span, varKind=$varKind, kind=$kind)"
    }

}

sealed interface NodeSymbol : Symbol {
    val parents: MutableList<InterfaceNodeSymbol>
    override val type: Type get() = ComplexType(name)
    override val kind: Kind get() = Kind.TYP
}

data class ConcreteNodeSymbol(
    override val name: String,
    val fields: List<VarSymbol>,
    override val parents: MutableList<InterfaceNodeSymbol>,
    override val span: SourceSpan
) : NodeSymbol, CallableSymbol {
    override val params: List<VarSymbol> get() = fields
    override val returnType: Type get() = ComplexType(name)

    // a node is declared once; `parents` grows after the symbol is used as a key, so it must not be hashed
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class InterfaceNodeSymbol(
    override val name: String,
    override val parents: MutableList<InterfaceNodeSymbol>,
    override val span: SourceSpan
) : NodeSymbol {
    // a node is declared once; `parents` grows after the symbol is used as a key, so it must not be hashed
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class EnumSymbol(
    override val name: String,
    val items: List<EnumItemSymbol>,
    override val span: SourceSpan
) : Symbol {
    override val type: Type get() = ComplexType(name)
    override val kind: Kind = Kind.TYP
}

data class EnumItemSymbol(
    override val name: String,
    val enumType: String,
    val parameters: List<Pair<String, Type>>,
    override val span: SourceSpan
) : Symbol {
    override val type: Type by lazy {
        if (kind == Kind.VAL) {
            ComplexType(enumType)
        }
        else {
            FunctionType(parameters.map { it.second }, ComplexType(enumType))
        }
    }
    override val kind: Kind = if (parameters.isEmpty()) Kind.VAL else Kind.MTH
}

data class RecordSymbol(
    override val name: String,
    val fields: List<VarSymbol>,
    override val span: SourceSpan?,
    override val type: Type = ComplexType(name),
    /** Built-in types whose values can not be created with a constructor call (e.g. `Graph`). */
    val opaque: Boolean = false,
) : Symbol, CallableSymbol {
    override val kind: Kind = Kind.TYP
    override val params: List<VarSymbol> get() = fields
    override val returnType: Type get () = type
}

data class FunctionSymbol(
    val receiver: Symbol?,
    override val name: String,
    override val params: List<VarSymbol>,
    override val returnType: Type,
    override val span: SourceSpan,
    /** `op fun`: called for an operator applied to its receiver, e.g. `plus` for `a + b`. */
    val isOperator: Boolean = false,
    /** For builtin functions: the operation of the runtimes it is, e.g. `str.upper`. */
    val builtin: String? = null,
) : CallableSymbol {
    override val type: Type get() = FunctionType(params.map { it.type }, returnType)
    override val kind: Kind = Kind.MTH
}

sealed interface AttributeSymbol : Symbol {
    override val name: String
    override val kind: Kind get() = Kind.VAL
}

sealed interface NodeBoundAttributeSymbol : AttributeSymbol {
    val owner: NodeSymbol
}

data class SynSymbol(
    override val owner: NodeSymbol,
    override val name: String,
    override val type: Type,
    override val span: SourceSpan,
    /** `false` for a declaration without equation (`syn Expr.type: Type`), which subtypes must implement. */
    val hasBody: Boolean = owner is ConcreteNodeSymbol,
) : NodeBoundAttributeSymbol {
    val isDefinition: Boolean get() = !hasBody
    val isImplementation: Boolean get() = hasBody
}

/**
 * `circular(init) Node.attr: Type = body`: an attribute whose equation may depend on itself (also through
 * other nodes and other circular attributes). Its value is the fixpoint reached by starting from `init`.
 */
data class CircularSymbol(
    override val owner: NodeSymbol,
    override val name: String,
    override val type: Type,
    override val span: SourceSpan
) : NodeBoundAttributeSymbol

data class InhSymbol(
    override val name: String,
    override val type: Type,
    override val span: SourceSpan
) : AttributeSymbol

data class CollSymbol(
    override val owner: ConcreteNodeSymbol,
    override val name: String,
    override val type: Type,
    override val span: SourceSpan
) : NodeBoundAttributeSymbol

data class ParserSymbol(
    override val name: String,
    override val span: SourceSpan,
    /** Parses bytes (`List<int>`) instead of text; enables bit patterns and `@u1`, `@uint(n)`, ... */
    val binary: Boolean = false,
    /** Skipped before every terminal; `null` skips nothing. */
    val skip: String? = if (binary) null else "\\s+",
    /** Words that regex terminals (which are not plain literals) never match, e.g. identifiers. */
    val keywords: List<String> = emptyList(),
) : Symbol {
    override val type: Type get() = ParserType(name)
    override val kind: Kind get() = Kind.TYP
}

interface ParserRuleSymbol : CallableSymbol {
    val parser: ParserSymbol

    override val params: List<VarSymbol> get() = listOf(
        VarSymbol("source", if (parser.binary) ListType(IntType) else StrType, span, VarSymbol.VarKind.PARAMETER)
    )

    override val type: Type get() = FunctionType(params.map { it.type }, returnType)

    override val kind: Kind get() = Kind.MTH
}

data class InterfaceParserRuleSymbol(
    override val parser: ParserSymbol,
    override val name: String,
    override val returnType: Type,
    override val span: SourceSpan
) : ParserRuleSymbol

data class ConcreteParserRuleSymbol(
    override val parser: ParserSymbol,
    override val name: String,
    override val returnType: Type,
    override val span: SourceSpan
) : ParserRuleSymbol


/** A port of flows: `port onTrue` or, looked up through the enclosing flows, `inh port break`. */
data class PortSymbol(
    override val name: String,
    val inherited: Boolean,
    override val span: SourceSpan?
) : Symbol {
    override val type: Type get() = PortType
    override val kind: Kind get() = Kind.VAL
}

/** `graph cfg: Function`: a graph definition. Its members (`get`, `of`, `succ`, `pred`) are [methods]. */
data class FlowGraphSymbol(
    override val name: String,
    val root: NodeSymbol,
    override val span: SourceSpan
) : Symbol {
    override val type: Type get() = FlowGraphType(name, root.name)
    override val kind: Kind get() = Kind.VAL

    val methods: List<FunctionSymbol> by lazy {
        val vertex = ComplexType(VERTEX)
        listOf(
            FunctionSymbol(this, "get", listOf(VarSymbol("root", root.type, null, VarSymbol.VarKind.PARAMETER)), ComplexType(GRAPH), span),
            FunctionSymbol(this, "of", listOf(VarSymbol("vertex", vertex, null, VarSymbol.VarKind.PARAMETER)), OptionalType(ComplexType(GRAPH)), span),
            FunctionSymbol(this, "succ", listOf(VarSymbol("vertex", vertex, null, VarSymbol.VarKind.PARAMETER)), ListType(vertex), span),
            FunctionSymbol(this, "pred", listOf(VarSymbol("vertex", vertex, null, VarSymbol.VarKind.PARAMETER)), ListType(vertex), span),
        )
    }
}

/** A flow rule: `flow cfg: While(cond, body) { ... }`. */
data class FlowSymbol(
    val graph: FlowGraphSymbol,
    val node: NodeSymbol,
    override val span: SourceSpan
) : Symbol {
    override val name: String get() = "${graph.name}:${node.name}"
    override val type: Type get() = node.type
    override val kind: Kind get() = Kind.VAL
}

/** Position of built-in declarations. */
val builtinSpan: SourceSpan = SourceSpan(0, 0, 0, 0)

const val VERTEX = "Vertex"
const val GRAPH = "Graph"
const val EDGE = "Edge"
const val SPAN = "Span"
