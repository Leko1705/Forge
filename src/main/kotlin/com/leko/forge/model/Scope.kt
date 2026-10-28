package com.leko.forge.model

class Scope {
    val entries: Map<String, Symbol> field = mutableMapOf<String, Symbol>()

    fun enter(symbol: Symbol) {
        entries[symbol.name] = symbol
    }

    val members: Iterable<Symbol> get() = entries.values
}