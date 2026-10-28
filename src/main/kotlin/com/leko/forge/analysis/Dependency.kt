package com.leko.forge.analysis

import ForgeParser
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.context.Context
import com.leko.forge.model.SynSymbol
import com.leko.forge.util.Log

/**
 * Checks that every concrete node has an equation for every synthesized attribute
 * it inherits from its interfaces (either its own or a default equation of an interface).
 *
 * Circularity is not checked statically: whether `syn List.len = .. this.tail.len ..`
 * is circular depends on the tree, not on the attribute. Generated compilers detect
 * circular attribute evaluation at runtime (ForgeCircularityException), like JastAdd.
 */
class Dependency private constructor(context: Context) {

    companion object {
        fun instance(context: Context): Dependency =
            context.getServices(Dependency::class) { Dependency(context) }
    }

    private val symTab = SymTab.instance(context)

    private val typeMap = TypeMap.instance(context)

    private val log = Log.instance(context)

    private val chk = Check.instance(context)


    fun check(root: ForgeParser.RootContext) {
        checkAllInterfaceSynsAreImplemented()
    }

    private fun checkAllInterfaceSynsAreImplemented() {
        for (concreteNode in symTab.concreteNodes.values) {
            val parents = typeMap.allParentsOf(concreteNode)
            val concreteSyns = typeMap.nodeAttributes[concreteNode]?.filterIsInstance<SynSymbol>() ?: emptyList()
            val declared = parents.flatMap { parent -> typeMap.nodeAttributes[parent]?.filterIsInstance<SynSymbol>() ?: emptyList() }
            for (name in declared.map { it.name }.distinct()) {
                val declarations = declared.filter { it.name == name }
                val found = concreteSyns.find { name == it.name }
                if (found == null) {
                    if (declarations.none { it.hasEquation }) {
                        log.diag(
                            missingSynAttributeImplementationFor(
                                concreteNode.span,
                                concreteNode.name,
                                "${declarations.first().owner.name}.$name"
                            )
                        )
                    }
                }
                else {
                    for (declaration in declarations) chk.checkAssignable(declaration.type, found.type, found.span)
                }
            }
        }
    }

}
