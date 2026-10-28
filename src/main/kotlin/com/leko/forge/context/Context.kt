package com.leko.forge.context

import kotlin.reflect.KClass

class Context {

    private val services: MutableMap<KClass<*>, Any> = mutableMapOf()

    fun <T : Any> getServices(clazz: KClass<T>, provider: () -> T): T =
        @Suppress("UNCHECKED_CAST") (services.getOrPut(clazz, provider) as T)

}