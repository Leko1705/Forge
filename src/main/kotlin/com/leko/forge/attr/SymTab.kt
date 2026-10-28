package com.leko.forge.attr

import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.Type


class SymTab private constructor() {
    companion object {
        fun instance(context: Context): SymTab =
            context.getServices(SymTab::class) { SymTab() }
    }

    val table = mutableMapOf<Type, Symbol>(
        nullSymbol.type to nullSymbol,
        intSymbol.type to intSymbol,
        floatSymbol.type to floatSymbol,
        boolSymbol.type to boolSymbol,
        strSymbol.type to strSymbol,
    )

    val interfaceNodes get() = globals<InterfaceNodeSymbol>()

    val concreteNodes get() = globals<ConcreteNodeSymbol>()

    val enums get() = globals<EnumSymbol>()

    val parsers get() = globals<ParserSymbol>()

    val inheritedAttributes = mutableMapOf<String, InhSymbol>()


    private inline fun <reified T> globals() =
        table.filterValues { it is T }.mapValues { it.value as T }.toMap()

    operator fun get(type: Type): Symbol? = table[type]

    operator fun get(name: String): Symbol? = table.values.firstOrNull { it.name == name }

    operator fun set(type: Type, symbol: Symbol) {
        table[type] = symbol
    }

    operator fun contains(type: Type): Boolean = type in table

    operator fun contains(type: String): Boolean = table.values.any { it.name == type }
}
