package com.leko.forge.llir

class Tools {

    private var nodeMap: MutableMap<Value, Value> = mutableMapOf()

    fun canonicalize(value: Value): Value = nodeMap.getOrPut(value) { value }

}
