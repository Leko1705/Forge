package com.leko.forge.llir

class Tools {

    private var nodeMap: MutableMap<Value, Value> = mutableMapOf()

    fun canonicalize(value: Value): Value {
        val value = value.canonicalized
        if (value in nodeMap) return value
        nodeMap[value] = value
        return value
    }

}