package com.leko.forge.model

data class Env<T>(
    val outer: Env<T>?,
    val next: Env<T>?,
    val value: T
) {
    constructor(value: T) : this(null, null, value)

    fun child(value: T): Env<T> = Env(outer, this, value)
}
