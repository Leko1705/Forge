package com.leko.forge.attr

import com.leko.forge.context.Context
import com.leko.forge.model.*


class TypeMap private constructor() {

    companion object {
        fun instance(context: Context): TypeMap =
            context.getServices(TypeMap::class) { TypeMap() }
    }

    val nodeIfaceImpls = mutableMapOf<ConcreteNodeSymbol, List<InterfaceNodeSymbol>>()

    val ifaceParents = mutableMapOf<InterfaceNodeSymbol, List<InterfaceNodeSymbol>>()

    val methods = mutableMapOf<Symbol, List<FunctionSymbol>>()

    val nodeAttributes = mutableMapOf<NodeSymbol, List<NodeBoundAttributeSymbol>>()

    val parserRuleDefs = mutableMapOf<ParserSymbol, List<InterfaceParserRuleSymbol>>()

    val parserRuleImpls = mutableMapOf<ParserSymbol, List<ConcreteParserRuleSymbol>>()


    fun allParentsOf(node: NodeSymbol): Set<InterfaceNodeSymbol> {
        if (node is ConcreteNodeSymbol) {
            val parents = nodeIfaceImpls[node] ?: return emptySet()
            return buildSet {
                for (parent in parents) {
                    addAll(allParentsOfImpl(parent))
                }
            }
        }
        else {
            val parents = ifaceParents[node as InterfaceNodeSymbol] ?: return emptySet()
            return buildSet {
                for (parent in parents) {
                    addAll(allParentsOfImpl(parent))
                }
            }
        }
    }

    private fun allParentsOfImpl(node: InterfaceNodeSymbol): Set<InterfaceNodeSymbol> {
        val parents = ifaceParents[node] ?: return mutableSetOf(node)
        return buildSet {
            add(node)
            for (parent in parents) {
                addAll(allParentsOfImpl(parent))
            }
        }
    }
}
