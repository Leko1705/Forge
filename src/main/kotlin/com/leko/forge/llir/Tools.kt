package com.leko.forge.llir

/** Shared state of the optimization phases. */
class Tools {

    private val canonical: MutableMap<Value, Value> = mutableMapOf()

    /** Returns the canonical instance of a structurally equal value (hash consing). */
    fun canonicalize(value: Value): Value = canonical.getOrPut(value) { value }

}
