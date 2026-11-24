package com.leko.forge.attr

import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.*

/**
 * Whether a type has the members of a trait: the rules the analysis checks bounds with (Attr), and editors show
 * which declarations implement a trait with.
 */
class Traits private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Traits = context.getServices(Traits::class) { Traits(context) }
    }

    private val resolve = Resolve.instance(context)
    private val types = Types.instance(context)

    /** What [actual] has for a member of a trait. */
    sealed interface Implementation {
        /** A method or property ([symbol] a [FunctionSymbol]), or for a `val` an attribute or a field. */
        data class Found(val symbol: Symbol) : Implementation
        /** For a `val`: an attribute or a field, but of another type. */
        data class WrongType(val symbol: Symbol) : Implementation
        data object Missing : Implementation
    }

    /** What [actual] (not a type parameter) has for [member]; `Self` in the member is [actual]. */
    fun implementation(actual: Type, member: TraitMember): Implementation {
        if (actual is OptionalType || actual == NullType || actual == NothingType || actual == ErrorType) return Implementation.Missing
        val self = mapOf(SELF to actual)
        val resolved = resolve.resolve(actual, member.name, Kind.VAL, if (member.isProperty) Kind.VAL else Kind.MTH)
        // a `val` member: also an attribute of a node (syn, circular, coll; inherited ones are read on `this`
        // only) or a field of a record or a node, which are read like a field too
        if (member.isProperty && (resolved is NodeBoundAttributeSymbol || resolved is VarSymbol)) {
            return if (types.isAssignable(member.returnType.substitute(self), resolved.type)) Implementation.Found(resolved)
                else Implementation.WrongType(resolved)
        }
        val found = resolved as? FunctionSymbol ?: return Implementation.Missing
        // an operator of the trait needs an operator of the type
        val fits = (!member.isOperator || found.isOperator) && found.params.size == member.params.size &&
            found.params.zip(member.params).all { (p, m) -> types.isAssignable(p.type, m.type.substitute(self)) } &&
            types.isAssignable(member.returnType.substitute(self), found.returnType)
        return if (fits) Implementation.Found(found) else Implementation.Missing
    }

    /** What [actual] has for each member of [trait], if it has all of them; `null` if it misses one. */
    fun implementations(actual: Type, trait: TraitSymbol): Map<TraitMember, Symbol>? =
        trait.members.associateWith { member ->
            (implementation(actual, member) as? Implementation.Found)?.symbol ?: return null
        }
}
