package com.leko.forge.types


sealed interface Type {
    override fun toString(): String
}

sealed interface CallableType : Type {
    val parameters: List<Type>
    val returnType: Type
    override fun toString(): String
}

object NullType : Type {
    override fun toString(): String = "null"
}

object IntType : Type {
    override fun toString(): String = "int"
}

/** A 64-bit integer: `long`, literals `1L`. */
object LongType : Type {
    override fun toString(): String = "long"
}

object FloatType : Type {
    override fun toString(): String = "float"
}

object BoolType : Type {
    override fun toString(): String = "bool"
}

object StrType : Type {
    override fun toString(): String = "str"
}

data class ListType(val elem: Type) : Type {
    override fun toString(): String = "List<$elem>"
}

/** A set: no element twice; iterated in the order the elements were added. */
data class SetType(val elem: Type) : Type {
    override fun toString(): String = "Set<$elem>"
}

data class MapType(val key: Type, val value: Type) : Type {
    override fun toString(): String = "Map<$key, $value>"
}

class OptionalType(elem: Type) : Type {
    val elem: Type = if (elem is OptionalType) elem.elem else elem
    override fun toString(): String = "$elem?"
    override fun equals(other: Any?): Boolean {
        return other is OptionalType && elem == other.elem
    }
    override fun hashCode(): Int = elem.hashCode() * 31
}

/**
 * A node, record or enum, by its (qualified) name; [args] are the type arguments of a generic record or enum:
 * `Pair<int, str>`. Generic types are covariant: an `Option<Nothing>` is an `Option<int>`.
 */
data class ComplexType(val name: String, val args: List<Type> = emptyList()) : Type {
    override fun toString(): String = displayName(name) + if (args.isEmpty()) "" else args.joinToString(", ", "<", ">")
}

/** `(int, str)`: a tuple of values of these types; its elements are `t.0`, `t.1`, ... */
data class TupleType(val elems: List<Type>) : Type {
    override fun toString(): String = elems.joinToString(", ", "(", ")")
}

/** Separates the names of modules from the names of their declarations inside the compiler and generated code. */
const val MODULE_SEPARATOR = "__"

/** How a qualified name is written: the declarations of modules are named `jvm__Insn` inside the compiler. */
fun displayName(name: String): String = name.replace(MODULE_SEPARATOR, "::")

data class FunctionType(override val parameters: List<Type>, override val returnType: Type) : CallableType {
    override fun toString(): String = "(${parameters.joinToString(", ")}) -> $returnType"
}

data class ParserType(val name: String) : Type {
    override fun toString(): String = name
}

data class TerminalRuleProduction(val actual: Type): Type {
    init {
        assert(actual !is TerminalRuleProduction)
    }
}

/** A port of a flow (`next`, `onTrue`, ...), only usable inside flows. */
object PortType : Type {
    override fun toString(): String = "Port"
}

/** The port of a child inside a flow, e.g. `onTrue(cond)`. */
object PortRefType : Type {
    override fun toString(): String = "PortRef"
}

/** A graph definition (`graph cfg: Function`), whose graphs are built per [root] node. */
data class FlowGraphType(val name: String, val root: String) : Type {
    override fun toString(): String = "FlowGraph<$root>"
}

/** A type parameter of a generic function: `T` in `fun <T> List<T>.distinct(): List<T>`. */
data class TypeVar(val name: String) : Type {
    // the type parameter of a parameter whose type is a trait (`x: Printable`) is shown as the trait
    override fun toString(): String = com.leko.forge.enter.traitOfParameter(name)?.let { displayName(it) } ?: name
}

/** The type of `fail(message)`, which never returns: it can be used wherever any value is expected. */
object NothingType : Type {
    override fun toString(): String = "Nothing"
}

object ArbitraryType : Type {
    override fun toString(): String = "*"
}

object ErrorType : Type {
    override fun toString(): String = "ERROR"
}
