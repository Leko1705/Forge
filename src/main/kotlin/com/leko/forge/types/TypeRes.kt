package com.leko.forge.types

import ForgeParser


fun ForgeParser.TypeContext.toType(): Type {
    return when (this) {
        is ForgeParser.ListTypeContext -> ListType(this.type().toType())
        is ForgeParser.SetTypeContext -> SetType(this.type().toType())
        is ForgeParser.MapTypeContext -> MapType(this.key.toType(), this.value.toType())
        is ForgeParser.OptionalTypeContext -> OptionalType(this.ty.toType())
        is ForgeParser.FunctionTypeContext -> FunctionType(this.params.map { it.toType() }, this.ret.toType())
        is ForgeParser.TupleTypeContext -> TupleType(this.elems.map { it.toType() })
        is ForgeParser.ParenTypeContext -> this.type().toType()
        is ForgeParser.NamedTypeContext -> {
            when (val name = this.name.text) {
                "int" -> IntType
                "long" -> LongType
                "float" -> FloatType
                "boolean", "bool" -> BoolType
                "str" -> StrType
                // never returns, e.g. a function that fails: fun error(m: str): Nothing = fail(m)
                "Nothing" -> NothingType
                else -> ComplexType(name, this.args.map { it.toType() })
            }
        }
        else -> throw IllegalArgumentException("Unsupported Type")
    }
}
