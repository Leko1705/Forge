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

data class ComplexType(val name: String) : Type {
    override fun toString(): String = name
}

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

object ArbitraryType : Type {
    override fun toString(): String = "*"
}

object ErrorType : Type {
    override fun toString(): String = "ERROR"
}
