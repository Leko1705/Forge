package com.leko.forge.types

/** The type with the names of [params] as type variables: `List<T>` with T a parameter is `List<TypeVar(T)>`. */
fun Type.withTypeParams(params: Collection<String>): Type = if (params.isEmpty()) this else when (this) {
    is ComplexType -> if (name in params && args.isEmpty()) TypeVar(name) else copy(args = args.map { it.withTypeParams(params) })
    is ListType -> ListType(elem.withTypeParams(params))
    is SetType -> SetType(elem.withTypeParams(params))
    is MapType -> MapType(key.withTypeParams(params), value.withTypeParams(params))
    is OptionalType -> OptionalType(elem.withTypeParams(params))
    is TupleType -> TupleType(elems.map { it.withTypeParams(params) })
    is FunctionType -> FunctionType(parameters.map { it.withTypeParams(params) }, returnType.withTypeParams(params))
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
    is ComplexType -> if (args.isEmpty()) this else copy(args = args.map { it.substitute(bindings) })
    is TupleType -> TupleType(elems.map { it.substitute(bindings) })
    else -> this
}

fun Type.hasTypeVars(): Boolean = when (this) {
    is TypeVar -> true
    is ListType -> elem.hasTypeVars()
    is SetType -> elem.hasTypeVars()
    is MapType -> key.hasTypeVars() || value.hasTypeVars()
    is OptionalType -> elem.hasTypeVars()
    is FunctionType -> parameters.any { it.hasTypeVars() } || returnType.hasTypeVars()
    is ComplexType -> args.any { it.hasTypeVars() }
    is TupleType -> elems.any { it.hasTypeVars() }
    else -> false
}

/** The names of the type variables of the type. */
fun Type.typeVars(): Set<String> = when (this) {
    is TypeVar -> setOf(name)
    is ListType -> elem.typeVars()
    is SetType -> elem.typeVars()
    is MapType -> key.typeVars() + value.typeVars()
    is OptionalType -> elem.typeVars()
    is FunctionType -> parameters.flatMap { it.typeVars() }.toSet() + returnType.typeVars()
    is ComplexType -> args.flatMap { it.typeVars() }.toSet()
    is TupleType -> elems.flatMap { it.typeVars() }.toSet()
    else -> emptySet()
}

/**
 * Binds the type variables of [pattern] so that it becomes [actual] (`List<T>` and `List<int>` bind T to int).
 * A variable bound before keeps its binding, unless it was bound to `Nothing` (an `Option.NONE`, `[]`): then a
 * more specific type replaces it. Whether the types then fit is checked by the caller.
 */
fun unify(pattern: Type, actual: Type, bindings: MutableMap<String, Type>) {
    when {
        pattern is TypeVar -> {
            if (actual == ErrorType || actual == NothingType || actual == ArbitraryType || actual == NullType) {
                if (actual == NullType && pattern.name !in bindings) bindings[pattern.name] = NullType
                return
            }
            val bound = bindings[pattern.name]
            if (bound == null || bound == NullType || bound.isUninformative()) {
                bindings[pattern.name] = if (bound == NullType) OptionalType(actual) else actual
            }
        }
        pattern is ListType && actual is ListType -> unify(pattern.elem, actual.elem, bindings)
        pattern is SetType && actual is SetType -> unify(pattern.elem, actual.elem, bindings)
        pattern is MapType && actual is MapType -> {
            unify(pattern.key, actual.key, bindings)
            unify(pattern.value, actual.value, bindings)
        }
        pattern is OptionalType && actual is OptionalType -> unify(pattern.elem, actual.elem, bindings)
        pattern is OptionalType -> unify(pattern.elem, actual, bindings)
        pattern is ComplexType && actual is ComplexType && pattern.name == actual.name && pattern.args.size == actual.args.size ->
            pattern.args.zip(actual.args).forEach { (p, a) -> unify(p, a, bindings) }
        pattern is TupleType && actual is TupleType && pattern.elems.size == actual.elems.size ->
            pattern.elems.zip(actual.elems).forEach { (p, a) -> unify(p, a, bindings) }
        pattern is FunctionType && actual is FunctionType && pattern.parameters.size == actual.parameters.size -> {
            pattern.parameters.zip(actual.parameters).forEach { (p, a) -> unify(p, a, bindings) }
            unify(pattern.returnType, actual.returnType, bindings)
        }
    }
}

/** A type that says nothing about its elements: `Nothing`, or a generic type of `Nothing`s (`List<Nothing>`). */
fun Type.isUninformative(): Boolean = when (this) {
    NothingType, ArbitraryType -> true
    is ListType -> elem.isUninformative()
    is SetType -> elem.isUninformative()
    is MapType -> key.isUninformative() && value.isUninformative()
    is ComplexType -> args.isNotEmpty() && args.all { it.isUninformative() }
    else -> false
}
