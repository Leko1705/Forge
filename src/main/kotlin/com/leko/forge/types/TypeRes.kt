package com.leko.forge.types

import ForgeParser


fun ForgeParser.TypeContext.toType(): Type {
    return when (this) {
        is ForgeParser.ListTypeContext -> ListType(this.type().toType())
        is ForgeParser.MapTypeContext -> MapType(this.key.toType(), this.value.toType())
        is ForgeParser.OptionalTypeContext -> OptionalType(this.ty.toType())
        is ForgeParser.NamedTypeContext -> {
            when (val name = this.name.text) {
                "int" -> IntType
                "float" -> FloatType
                "bool", "boolean" -> BoolType
                "str" -> StrType
                else -> ComplexType(name)
            }
        }
        else -> throw IllegalArgumentException("Unsupported Type")
    }
}
