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

val strSymbol = primitive("str", StrType)

fun listSymbol(listType: ListType) = primitive("List", listType) {
    add(VarSymbol("size", IntType, null, VarSymbol.VarKind.RECORD_FIELD))
}

/** members every node has: the source position (1-based, 0 for nodes not created by a parser) */
val nodeBuiltins: Map<String, VarSymbol> = listOf("line", "column").associateWith {
    VarSymbol(it, IntType, null, VarSymbol.VarKind.NODE_BUILTIN)
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
        /** builtin members of every node: `line` and `column` of its source position */
        NODE_BUILTIN,

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
}

data class InterfaceNodeSymbol(
    override val name: String,
    override val parents: MutableList<InterfaceNodeSymbol>,
    override val span: SourceSpan
) : NodeSymbol

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
    override val type: Type = ComplexType(name)
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
    override val span: SourceSpan
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
    /** whether the declaration comes with an equation (on interfaces: a default equation) */
    val hasEquation: Boolean = owner is ConcreteNodeSymbol,
) : NodeBoundAttributeSymbol {
    val isDefinition: Boolean get() = owner is InterfaceNodeSymbol
    val isImplementation: Boolean get() = !isDefinition
}

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
    override val span: SourceSpan
) : Symbol {
    /** the parser this parser extends (`parser Ext : Base`), it inherits all rules of its parent */
    var parent: ParserSymbol? = null

    val lineage: List<ParserSymbol> get() = generateSequence(this) { it.parent }.take(64).toList()

    override val type: Type get() = ParserType(name)
    override val kind: Kind get() = Kind.TYP
}

interface ParserRuleSymbol : CallableSymbol {
    val parser: ParserSymbol

    override val params: List<VarSymbol> get() = listOf(VarSymbol("source", StrType, span))

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
