package com.leko.forge.types

import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.Resolve
import com.leko.forge.attr.SymTab
import com.leko.forge.context.Context
import com.leko.forge.model.ConcreteNodeSymbol
import com.leko.forge.model.EnumSymbol
import com.leko.forge.model.InterfaceNodeSymbol
import com.leko.forge.model.NodeSymbol
import com.leko.forge.model.Symbol


class Types private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Types =
            context.getServices(Types::class) { Types(context) }
    }

    private val symTab = SymTab.instance(context)

    private val typeMap = TypeMap.instance(context)

    private val resolve = Resolve.instance(context)


    fun isAssignable(expected: Symbol, got: Symbol): Boolean {
        return if (expected is NodeSymbol && got is NodeSymbol) {
            areNodesAssignable(expected, got)
        } else {
            expected == got
        }
    }


    private fun areNodesAssignable(expected: NodeSymbol, got: NodeSymbol): Boolean {
        val visited = mutableSetOf<NodeSymbol>()

        fun visit(curr: NodeSymbol): Boolean {
            if (visited.contains(curr)) return false
            visited.add(curr)
            return curr == expected || when (curr) {
                is ConcreteNodeSymbol -> curr.parents.any { visit(it) }
                is InterfaceNodeSymbol -> curr.parents.any { visit(it) }
            }
        }

        return visit(got)
    }

    /**
     * The bounds of the type parameters of the function being checked: inside `fun <T: Expr> f(x: T)`, a `T`
     * is an `Expr`. Set by Attr while it checks a generic function.
     */
    var typeBounds: Map<String, List<Type>> = emptyMap()

    fun isAssignable(expected: Type, got: Type): Boolean {
        if (expected == ErrorType || got == ErrorType) return true
        if (got == NothingType) return true
        if (got is ArbitraryType) return false
        if (expected is ArbitraryType) return true
        if (expected == got) return true
        if (expected is OptionalType && got is NullType) return true
        if (expected is OptionalType && isAssignable(expected.elem, got)) return true
        if (expected is OptionalType && got is OptionalType) return isAssignable(expected.elem, got.elem)
        if (expected is ComplexType && got is ComplexType) {
            // generic records and enums are covariant
            if (expected.name == got.name && expected.args.size == got.args.size) {
                return expected.args.zip(got.args).all { (e, g) -> isAssignable(e, g) }
            }
            val expSym = resolve.resolve(expected) ?: return false
            val gotSym = resolve.resolve(got) ?: return false
            return isAssignable(expSym, gotSym)
        }
        if (expected is TupleType && got is TupleType) {
            return expected.elems.size == got.elems.size && expected.elems.zip(got.elems).all { (e, g) -> isAssignable(e, g) }
        }
        if (expected is FunctionType && got is FunctionType) {
            // parameters contravariant, the result covariant
            return expected.parameters.size == got.parameters.size &&
                expected.parameters.zip(got.parameters).all { (e, g) -> isAssignable(g, e) } &&
                isAssignable(expected.returnType, got.returnType)
        }
        // a type parameter with a bound is one of its bound: fun <T: Expr> ... T is an Expr
        if (got is TypeVar) return typeBounds[got.name].orEmpty().any { isAssignable(expected, it) }
        if (expected is ListType && got is ListType) {
            return isAssignable(expected.elem, got.elem)
        }
        if (expected is SetType && got is SetType) {
            return isAssignable(expected.elem, got.elem)
        }
        if (expected is MapType && got is MapType) {
            return isAssignable(expected.key, got.key)
                    && isAssignable(expected.value, got.value)
        }
        return false
    }

    fun sharedSuperType(t1: Type, t2: Type): Type? {
        if (t1 == ErrorType || t2 == ErrorType) return ErrorType
        if (t1 == t2) return t1
        if (t1 == NothingType) return t2
        if (t2 == NothingType) return t1
        if (t1 in listOf(IntType, LongType) && t2 in listOf(IntType, LongType)) return LongType
        if (t1 in listOf(IntType, LongType, FloatType) && t2 in listOf(IntType, LongType, FloatType)) return FloatType
        if (t1 is OptionalType && t2 is NullType) return t1
        if (t1 is NullType && t2 is OptionalType) return t2
        if (t1 is NullType && t2 is NullType) return NullType
        if (t1 is NullType && t2 !is OptionalType) return OptionalType(t2)
        if (t1 !is OptionalType && t2 is NullType) return OptionalType(t1)
        if (t1 is OptionalType && t2 is OptionalType) {
            val st = sharedSuperType(t1.elem, t2.elem) ?: return null
            return st as? OptionalType ?: OptionalType(st)
        }
        if (t1 is OptionalType) {
            val st = sharedSuperType(t1.elem, t2) ?: return null
            return st as? OptionalType ?: OptionalType(st)
        }
        if (t2 is OptionalType) {
            val st = sharedSuperType(t1, t2.elem) ?: return null
            return st as? OptionalType ?: OptionalType(st)
        }
        if (t1 is ComplexType && t2 is ComplexType && t1.name == t2.name && t1.args.size == t2.args.size) {
            val args = t1.args.zip(t2.args).map { (a, b) -> nestedJoin(a, b) ?: return null }
            return t1.copy(args = args)
        }
        if (t1 is TupleType && t2 is TupleType && t1.elems.size == t2.elems.size) {
            return TupleType(t1.elems.zip(t2.elems).map { (a, b) -> nestedJoin(a, b) ?: return null })
        }
        if (t1 is ListType && t2 is ListType) return ListType(nestedJoin(t1.elem, t2.elem) ?: return null)
        if (t1 is SetType && t2 is SetType) return SetType(nestedJoin(t1.elem, t2.elem) ?: return null)
        if (t1 is ComplexType && t2 is ComplexType) {
            val n1 = resolve.resolve(t1) as? NodeSymbol ?: return null
            val n2 = resolve.resolve(t2) as? NodeSymbol ?: return null
            return sharedSuperNode(n1, n2)?.type
        }
        return null
    }

    /** The shared supertype of type arguments: only one both are assignable to (no int to float inside a list). */
    private fun nestedJoin(a: Type, b: Type): Type? {
        val joined = sharedSuperType(a, b) ?: return null
        return joined.takeIf { isAssignable(it, a) && isAssignable(it, b) }
    }

    /**
     * The most specific node both nodes are assignable to, e.g. `JInsn` for `JPush` and `JLabel`, so that
     * `[JPush(1), JLabel("end")]` is a `List<JInsn>`. `null` if there are several equally specific ones.
     */
    private fun sharedSuperNode(n1: NodeSymbol, n2: NodeSymbol): NodeSymbol? {
        if (areNodesAssignable(n1, n2)) return n1
        if (areNodesAssignable(n2, n1)) return n2
        val shared = typeMap.allParentsOf(n1).filter { areNodesAssignable(it, n2) }
        // drop the candidates that are supertypes of other candidates
        val mostSpecific = shared.filter { c -> shared.none { o -> o != c && areNodesAssignable(c, o) } }
        return mostSpecific.singleOrNull()
    }
}
