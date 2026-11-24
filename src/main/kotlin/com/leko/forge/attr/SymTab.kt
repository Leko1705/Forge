package com.leko.forge.attr

import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.Type
import com.leko.forge.types.ComplexType


class SymTab private constructor() {
    companion object {
        fun instance(context: Context): SymTab =
            context.getServices(SymTab::class) { SymTab() }
    }

    val table = mutableMapOf<Type, Symbol>(
        nullSymbol.type to nullSymbol,
        intSymbol.type to intSymbol,
        floatSymbol.type to floatSymbol,
        longSymbol.type to longSymbol,
        boolSymbol.type to boolSymbol,
        strSymbol.type to strSymbol,
    )

    /** Top level functions by name (their types are not unique, so they are not part of [table]). */
    val functions = linkedMapOf<String, FunctionSymbol>()
    /** Functions of the standard library that one of the program hides; the standard library itself uses them. */
    val stdFunctions = linkedMapOf<String, FunctionSymbol>()

    val interfaceNodes get() = globals<InterfaceNodeSymbol>()

    val concreteNodes get() = globals<ConcreteNodeSymbol>()

    val enums get() = globals<EnumSymbol>()

    val parsers get() = globals<ParserSymbol>()

    val graphs get() = globals<FlowGraphSymbol>()

    val inheritedAttributes = mutableMapOf<String, InhSymbol>()

    /** Constants by name. */
    val constants = linkedMapOf<String, ConstSymbol>()

    /** Flow ports by name, including the built-in `next`. */
    val ports = linkedMapOf<String, PortSymbol>("next" to PortSymbol("next", false, null))


    private inline fun <reified T> globals() =
        table.filterValues { it is T }.mapValues { it.value as T }.toMap()

    /** Traits by name. */
    val traits = linkedMapOf<String, TraitSymbol>()

    /** Generic records and enums are entered without their type arguments: `Pair<int, str>` is `Pair`. */
    private fun key(type: Type): Type = if (type is ComplexType && type.args.isNotEmpty()) ComplexType(type.name) else type

    operator fun get(type: Type): Symbol? = table[key(type)]

    operator fun get(name: String): Symbol? = table.values.firstOrNull { it.name == name } ?: functions[name]

    operator fun set(type: Type, symbol: Symbol) {
        table[key(type)] = symbol
    }

    operator fun contains(type: Type): Boolean = key(type) in table

    operator fun contains(type: String): Boolean = table.values.any { it.name == type } || type in functions
}
