package com.leko.forge.attr

import com.leko.forge.context.Context
import com.leko.forge.model.ConcreteNodeSymbol
import com.leko.forge.model.EnumItemSymbol
import com.leko.forge.model.EnumSymbol
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
import com.leko.forge.model.nodeBuiltins
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
                Kind.MTH -> {
                    val methods = typeMap.methods[sym] ?: return null
                    methods.firstOrNull { it.name == member }
                }
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
            // the node itself first, then its interfaces (nearest first)
            val lineage = mutableListOf<NodeSymbol>(sym)
            var i = 0
            while (i < lineage.size) {
                for (p in lineage[i].parents) if (p !in lineage) lineage += p
                i++
            }
            return when (memberKind) {
                Kind.VAL -> {
                    if (sym is ConcreteNodeSymbol && sym.fields.any { it.name == member }) {
                        sym.fields.first { it.name == member }
                    } else {
                        lineage.firstNotNullOfOrNull { n -> typeMap.nodeAttributes[n]?.find { it.name == member } }
                            ?: symTab.inheritedAttributes[member]
                            ?: nodeBuiltins[member]
                    }
                }
                Kind.MTH -> lineage.firstNotNullOfOrNull { n -> typeMap.methods[n]?.firstOrNull { it.name == member } }
                else -> null
            }
        }
        if (sym is ParserSymbol) {
            if (receiverKind != Kind.TYP) return null
            if (memberKind != Kind.MTH) return null
            return resolveParserRule(sym, member)
        }
        return null
    }

    /**
     * The symbol of the rule [name] of [parser]: the abstract declaration if there is one, the
     * (first) concrete definition otherwise. Rules of parent parsers are inherited.
     */
    fun resolveParserRule(parser: ParserSymbol, name: String): Symbol? {
        for (p in parser.lineage) {
            typeMap.parserRuleDefs[p]?.firstOrNull { it.name == name }?.let { return it }
        }
        for (p in parser.lineage) {
            typeMap.parserRuleImpls[p]?.firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }
}
