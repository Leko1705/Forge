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

val longSymbol = primitive("long", LongType)

val boolSymbol = primitive("bool", BoolType)

val strSymbol = primitive("str", StrType)

fun setSymbol(setType: SetType) = primitive("Set", setType)

fun listSymbol(listType: ListType) = primitive("List", listType)

fun mapSymbol(mapType: MapType) = primitive("Map", mapType)

fun tupleSymbol(tupleType: TupleType) = primitive("Tuple", tupleType)

fun functionValueSymbol(functionType: FunctionType) = primitive("Function", functionType)

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
    override val span: SourceSpan,
    /** The type parameters of a generic enum: `T` in `enum Option<T> { NONE, SOME(value: T) }`. */
    val typeParams: List<String> = emptyList(),
) : Symbol {
    /** The enum with its type parameters as type variables; a use is the type with arguments for them. */
    override val type: Type get() = ComplexType(name, typeParams.map { TypeVar(it) })
    override val kind: Kind = Kind.TYP
}

data class EnumItemSymbol(
    override val name: String,
    val enumType: String,
    /** The fields; their types have the type parameters of the enum as type variables. */
    val parameters: List<Pair<String, Type>>,
    override val span: SourceSpan,
    val typeParams: List<String> = emptyList(),
) : Symbol {
    /** The type of the enum: `Option<T>`, an item of a generic enum is one with type variables. */
    val enumComplexType: ComplexType get() = ComplexType(enumType, typeParams.map { TypeVar(it) })
    override val type: Type by lazy {
        if (kind == Kind.VAL) {
            enumComplexType
        }
        else {
            FunctionType(parameters.map { it.second }, enumComplexType)
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
    /** The type parameters of a generic record: `A`, `B` in `record Pair<A, B> { first: A, second: B }`. */
    val typeParams: List<String> = emptyList(),
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
    /** The type parameters of a generic function: `T` in `fun <T> List<T>.distinct()`. */
    val typeParams: List<String> = emptyList(),
    /** For a use of a generic function with some types known: the declaration. */
    val generic: FunctionSymbol? = null,
    /** `val T.name: R`: read like a field, `x.name`; a method without parameters otherwise. */
    val isProperty: Boolean = false,
    /** The bounds of the type parameters: `fun <T: Ord & Expr>` is `T` to `[Ord, Expr]`. */
    val bounds: Map<String, List<Type>> = emptyMap(),
    /**
     * A member of a trait used on a type parameter (`x.compareTo(y)` with `x: T` and `T: Ord`): the type
     * parameter. The generic function receives the member of the actual type as a hidden parameter.
     */
    val witnessOf: String? = null,
    /** For a use of a generic function: what its type parameters are. */
    val typeArgs: Map<String, Type> = emptyMap(),
) : CallableSymbol {
    /** The declaration, also for a use of a generic function. */
    val declaration: FunctionSymbol get() = generic ?: this

    /** This function with type variables replaced: a use of a generic function. */
    fun instantiate(bindings: Map<String, Type>): FunctionSymbol = if (declaration.typeParams.isEmpty()) this else declaration.copy(
        params = declaration.params.map { VarSymbol(it.name, it.type.substitute(bindings), it.span, it.varKind) },
        returnType = declaration.returnType.substitute(bindings),
        generic = declaration,
        typeArgs = declaration.typeParams.associateWith { bindings[it] ?: TypeVar(it) },
    )

    override val type: Type get() = FunctionType(params.map { it.type }, returnType)
    override val kind: Kind = Kind.MTH
}

/**
 * `trait Ord { fun compareTo(other: Self): int }`: what a type parameter can require. A type has a trait if it
 * has its members (methods, `op fun`s, `val`s, also those of the standard library), with `Self` the type.
 */
data class TraitSymbol(
    override val name: String,
    val members: List<TraitMember>,
    override val span: SourceSpan,
) : Symbol {
    override val type: Type get() = ComplexType(name)
    override val kind: Kind get() = Kind.TYP
}

/** A member of a trait; its types use `Self` ([SELF]) for the type that has the trait. */
data class TraitMember(
    val name: String,
    val params: List<VarSymbol>,
    val returnType: Type,
    val isProperty: Boolean,
    val span: SourceSpan,
    /** `op fun`: an operator on values of a type parameter with the trait; a type needs an `op fun` for it. */
    val isOperator: Boolean = false,
)

/** The type variable that stands for the type that has a trait, in the members of the trait. */
const val SELF = "Self"

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
    /**
     * `child syn`: the value is a tree (a node, an optional node, a list or a map of nodes) that becomes a child of
     * the node; also the equations of an attribute declared so, and the [FORWARD] of a node with `by`.
     */
    var isChild: Boolean = false,
    /** The interface whose attributes the node forwards, for its [FORWARD] attribute. */
    val forwards: InterfaceNodeSymbol? = null,
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

/** The attribute of a node with `by` that is the tree it forwards to: `node N(...): I by tree`. */
const val FORWARD = "forward"

/** Position of built-in declarations. */
val builtinSpan: SourceSpan = SourceSpan(0, 0, 0, 0)

const val VERTEX = "Vertex"
const val GRAPH = "Graph"
const val EDGE = "Edge"
const val SPAN = "Span"

/**
 * The `ext fun`s of the standard library that the runtimes define as functions of their own, which the targets call
 * by name (`spanOf(node)`, `g.succ(v)`), unlike the other builtins, whose code each target writes where they are used
 * (`str.upper`): the functions without receiver named here, and the methods of [GRAPH].
 */
val RUNTIME_FUNCTIONS = setOf("graphOf", "spanOf")

/** Whether the `ext fun` [name] of the standard library, of [receiver] (`null` for none), is one of [RUNTIME_FUNCTIONS]. */
fun isRuntimeFunction(receiver: String?, name: String): Boolean =
    if (receiver == null) name in RUNTIME_FUNCTIONS else receiver == GRAPH

/** The names of the functions `op fun` can define. */
val OPERATORS = setOf(
    "plus", "minus", "times", "div", "rem", "concat", "shl", "shr", "ushr", "compareTo", "contains", "get", "slice",
    "neg", "not",
)

/**
 * What a call of a generic function passes for a member of a trait of a type parameter: the member of the type
 * argument ([symbol]), or, inside another generic function, the witness it received for its type parameter
 * [typeParam]. Neither for type arguments that are errors.
 */
data class Witness(
    val trait: TraitSymbol,
    val member: TraitMember,
    val actual: Type,
    val symbol: FunctionSymbol?,
    val typeParam: String?,
    /** The witnesses [symbol] itself receives, if it is a generic function with traits. */
    val nested: List<Witness> = emptyList(),
    /** For a `val` member: the attribute or the field of the type argument that is it, instead of a [symbol]. */
    val value: Symbol? = null,
)

/** The hidden parameter of a generic function for a member of a trait of one of its type parameters. */
fun witnessName(typeParam: String, trait: String, member: String): String = "__w_${typeParam}_${trait}_$member"
