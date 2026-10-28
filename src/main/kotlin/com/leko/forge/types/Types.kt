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

    fun isAssignable(expected: Type, got: Type): Boolean {
        if (expected == ErrorType || got == ErrorType) return true
        if (got is ArbitraryType) return false
        if (expected is ArbitraryType) return true
        if (expected == got) return true
        if (expected == FloatType && got == IntType) return true  // implicit int to float conversion
        if (expected is OptionalType && got is NullType) return true
        if (expected is OptionalType && isAssignable(expected.elem, got)) return true
        if (expected is OptionalType && got is OptionalType) return isAssignable(expected.elem, got.elem)
        if (expected is ComplexType && got is ComplexType) {
            val expSym = resolve.resolve(expected) ?: return false
            val gotSym = resolve.resolve(got) ?: return false
            return isAssignable(expSym, gotSym)
        }
        if (expected is ListType && got is ListType) {
            return isAssignableNested(expected.elem, got.elem)
        }
        if (expected is MapType && got is MapType) {
            return isAssignableNested(expected.key, got.key)
                    && isAssignableNested(expected.value, got.value)
        }
        return false
    }

    /** inside containers, ints are no floats (a list of ints is not converted element-wise) */
    private fun isAssignableNested(expected: Type, got: Type): Boolean =
        !(expected == FloatType && got == IntType) && isAssignable(expected, got)

    fun sharedSuperType(t1: Type, t2: Type): Type? {
        if (t1 == ErrorType || t2 == ErrorType) return ErrorType
        if (t1 == t2) return t1
        if (t1 in listOf(IntType, FloatType) && t2 in listOf(IntType, FloatType)) return FloatType
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
        if (t1 is ArbitraryType) return t2
        if (t2 is ArbitraryType) return t1
        if (t1 is ListType && t2 is ListType) {
            return ListType(sharedSuperType(t1.elem, t2.elem) ?: return null)
        }
        if (t1 is MapType && t2 is MapType) {
            return MapType(sharedSuperType(t1.key, t2.key) ?: return null, sharedSuperType(t1.value, t2.value) ?: return null)
        }
        if (t1 is ComplexType && t2 is ComplexType) {
            return commonNodeSuperType(t1, t2)
        }
        return null
    }

    /** The most specific node type both node types are assignable to, if it is unique. */
    private fun commonNodeSuperType(t1: ComplexType, t2: ComplexType): Type? {
        val s1 = resolve.resolve(t1) as? NodeSymbol ?: return null
        val s2 = resolve.resolve(t2) as? NodeSymbol ?: return null
        if (isAssignable(t1, t2)) return t1
        if (isAssignable(t2, t1)) return t2
        fun ancestors(sym: NodeSymbol): Set<NodeSymbol> {
            val seen = linkedSetOf<NodeSymbol>()
            fun walk(s: NodeSymbol) { for (p in s.parents) if (seen.add(p)) walk(p) }
            walk(sym)
            return seen
        }
        val common = ancestors(s1).intersect(ancestors(s2))
        val minimal = common.filter { c -> common.none { o -> o != c && areNodesAssignable(c, o) } }
        return minimal.singleOrNull()?.type
    }
}
