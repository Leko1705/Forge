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

object ArbitraryType : Type {
    override fun toString(): String = "*"
}

object ErrorType : Type {
    override fun toString(): String = "ERROR"
}
