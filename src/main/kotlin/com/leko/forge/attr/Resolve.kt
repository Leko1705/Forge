package com.leko.forge.attr

import com.leko.forge.context.Context
import com.leko.forge.model.setSymbol
import com.leko.forge.types.SetType
import com.leko.forge.model.ConcreteNodeSymbol
import com.leko.forge.model.EnumItemSymbol
import com.leko.forge.model.EnumSymbol
import com.leko.forge.model.FlowGraphSymbol
import com.leko.forge.model.Kind
import com.leko.forge.model.NodeSymbol
import com.leko.forge.model.ParserSymbol
import com.leko.forge.model.RecordSymbol
import com.leko.forge.model.Symbol
import com.leko.forge.model.VarSymbol
import com.leko.forge.model.boolSymbol
import com.leko.forge.model.floatSymbol
import com.leko.forge.model.intSymbol
import com.leko.forge.model.listSymbol
import com.leko.forge.model.mapSymbol
import com.leko.forge.model.nullSymbol
import com.leko.forge.model.strSymbol
import com.leko.forge.types.BoolType
import com.leko.forge.types.ComplexType
import com.leko.forge.types.FloatType
import com.leko.forge.types.IntType
import com.leko.forge.types.ListType
import com.leko.forge.types.MapType
import com.leko.forge.types.NullType
import com.leko.forge.types.OptionalType
import com.leko.forge.types.ParserType
import com.leko.forge.types.StrType
import com.leko.forge.types.Type


class Resolve private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Resolve =
            context.getServices(Resolve::class) { Resolve(context) }
    }


    val typeMap = TypeMap.instance(context)

    val symTab: SymTab = SymTab.instance(context)

    fun resolve(type: Type): Symbol? {
        if (type is ListType && type !in symTab) symTab[type] = listSymbol(type)
        if (type is MapType && type !in symTab) symTab[type] = mapSymbol(type)
        if (type is SetType && type !in symTab) symTab[type] = setSymbol(type)
        return symTab[type]
    }

    fun resolve(name: String): Symbol? {
        return symTab[name]
    }

    fun resolve(owner: Type, member: String, receiverKind: Kind, memberKind: Kind): Symbol? {
        assert(owner !is OptionalType) { "Resolve can not be applied to an optional type" }
        val sym = resolve(owner) ?: return null

        if (sym is EnumItemSymbol) {
            return resolve(ComplexType(sym.enumType), member, Kind.TYP, memberKind)
        }
        if (sym is RecordSymbol) {
            if (receiverKind != Kind.VAL) return null
            return when (memberKind) {
                Kind.VAL -> {
                    sym.fields.firstOrNull { it.name == member }
                }
                // methods the program defines, else builtin ones (str.upper(), List<T>.reversed(), ...)
                Kind.MTH -> typeMap.methods[sym]?.firstOrNull { it.name == member } ?: Builtins.method(sym, owner, member)
                else -> null
            }
        }
        if (sym is EnumSymbol) {
            return when (receiverKind) {
                Kind.TYP ->
                    sym.items.firstOrNull { it.name == member && it.kind == memberKind }

                Kind.VAL if memberKind == Kind.MTH ->
                    typeMap.methods[sym]?.firstOrNull { it.name == member }

                else -> null
            }
        }
        if (sym is NodeSymbol) {
            if (receiverKind != Kind.VAL) return null
            // own members first, then those of the supertypes (closest first)
            val owners = listOf(sym) + typeMap.allParentsOf(sym).sortedByDescending { typeMap.allParentsOf(it).size }
            return when (memberKind) {
                Kind.VAL -> {
                    if (sym is ConcreteNodeSymbol && sym.fields.any { it.name == member }) {
                        sym.fields.first { it.name == member }
                    } else {
                        owners.firstNotNullOfOrNull { owner -> typeMap.nodeAttributes[owner]?.find { it.name == member } }
                            ?: symTab.inheritedAttributes[member]
                    }
                }
                Kind.MTH -> owners.firstNotNullOfOrNull { owner -> typeMap.methods[owner]?.firstOrNull { it.name == member } }
                else -> null
            }
        }
        if (sym is FlowGraphSymbol) {
            if (receiverKind != Kind.VAL || memberKind != Kind.MTH) return null
            return sym.methods.firstOrNull { it.name == member }
        }
        if (sym is ParserSymbol) {
            if (receiverKind != Kind.TYP) return null
            if (memberKind != Kind.MTH) return null
            return typeMap.parserRuleDefs[sym]?.firstOrNull { it.name == member }
                ?: typeMap.parserRuleImpls[sym]?.firstOrNull { it.name == member }
        }
        return null
    }
}
