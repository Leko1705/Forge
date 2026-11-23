package com.leko.forge.types

/** The type with the names of [params] as type variables: `List<T>` with T a parameter is `List<TypeVar(T)>`. */
fun Type.withTypeParams(params: Collection<String>): Type = if (params.isEmpty()) this else when (this) {
    is ComplexType -> if (name in params) TypeVar(name) else this
    is ListType -> ListType(elem.withTypeParams(params))
    is SetType -> SetType(elem.withTypeParams(params))
    is MapType -> MapType(key.withTypeParams(params), value.withTypeParams(params))
    is OptionalType -> OptionalType(elem.withTypeParams(params))
    else -> this
}

/** The type with its type variables replaced by their [bindings]. */
fun Type.substitute(bindings: Map<String, Type>): Type = if (bindings.isEmpty()) this else when (this) {
    is TypeVar -> bindings[name] ?: this
    is ListType -> ListType(elem.substitute(bindings))
    is SetType -> SetType(elem.substitute(bindings))
    is MapType -> MapType(key.substitute(bindings), value.substitute(bindings))
    is OptionalType -> OptionalType(elem.substitute(bindings))
    is FunctionType -> FunctionType(parameters.map { it.substitute(bindings) }, returnType.substitute(bindings))
    else -> this
}

fun Type.hasTypeVars(): Boolean = when (this) {
    is TypeVar -> true
    is ListType -> elem.hasTypeVars()
    is SetType -> elem.hasTypeVars()
    is MapType -> key.hasTypeVars() || value.hasTypeVars()
    is OptionalType -> elem.hasTypeVars()
    else -> false
}

/**
 * Binds the type variables of [pattern] so that it becomes [actual] (`List<T>` and `List<int>` bind T to int).
 * A variable bound before keeps its binding; whether the types then fit is checked by the caller.
 */
fun unify(pattern: Type, actual: Type, bindings: MutableMap<String, Type>) {
    when {
        pattern is TypeVar -> if (pattern.name !in bindings && actual != ErrorType && actual != NothingType) bindings[pattern.name] = actual
        pattern is ListType && actual is ListType -> unify(pattern.elem, actual.elem, bindings)
        pattern is SetType && actual is SetType -> unify(pattern.elem, actual.elem, bindings)
        pattern is MapType && actual is MapType -> {
            unify(pattern.key, actual.key, bindings)
            unify(pattern.value, actual.value, bindings)
        }
        pattern is OptionalType && actual is OptionalType -> unify(pattern.elem, actual.elem, bindings)
        pattern is OptionalType -> unify(pattern.elem, actual, bindings)
    }
}
