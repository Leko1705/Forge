package com.leko.forge.analysis

import ForgeParser
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.symbol
import com.leko.forge.context.Context
import com.leko.forge.model.CollSymbol
import com.leko.forge.model.FunctionSymbol
import com.leko.forge.model.Symbol
import com.leko.forge.model.SynSymbol
import com.leko.forge.util.Log
import org.antlr.v4.runtime.tree.ParseTree


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
        checkCyclicDependencies(root)
    }

    private fun checkAllInterfaceSynsAreImplemented() {
        for (concreteNode in symTab.concreteNodes.values) {
            val parents = typeMap.allParentsOf(concreteNode)
            for (parent in parents) {
                val ifaceSyns = typeMap.nodeAttributes[parent]?.filterIsInstance<SynSymbol>()?.filter { it.isDefinition } ?: emptyList()
                val concreteSyns = typeMap.nodeAttributes[concreteNode]?.filterIsInstance<SynSymbol>()?.filter { it.isImplementation } ?: emptyList()
                for ((_, name, type) in ifaceSyns) {
                    val found = concreteSyns.find { name == it.name }
                    if (found == null) {
                        log.diag(
                            missingSynAttributeImplementationFor(
                                null,
                                concreteNode.name,
                                "${parent.name}.$name"
                            )
                        )
                    }
                    else {
                        chk.checkAssignable(type, found.type)
                    }
                }
            }
        }
    }


    private fun checkCyclicDependencies(root: ForgeParser.RootContext) {
        val references = mutableMapOf<Symbol, Set<Symbol>>()

        for (synImpl in root.synDef()) {
            if (synImpl.syn == null) continue
            val sym = synImpl.symbol ?: continue
            val refs = collectReferences(synImpl)
            references[sym] = refs
        }
        for (func in root.function()) {
            val sym = func.symbol ?: continue
            val refs = collectReferences(func)
            references[sym] = refs
        }
        for (coll in root.coll()) {
            val sym = coll.symbol ?: continue
            val refs = collectReferences(coll)
            val refsSoFar = references.getOrPut(sym) { mutableSetOf() } as MutableSet
            refsSoFar.addAll(refs)
        }
        for (contrib in root.contrib()) {
            val sym = contrib.symbol ?: continue
            val refs = collectReferences(contrib)
            val refsSoFar = references.getOrPut(sym) { mutableSetOf() } as MutableSet
            refsSoFar.addAll(refs)
        }

        val visited = mutableSetOf<Symbol>()
        val stack = mutableSetOf<Symbol>()

        fun visit(sym: Symbol) {
            if (sym in stack) {
                log.diag(cyclicDependencyDetected(null, sym.name))
                return
            }
            if (sym in visited) return
            visited.add(sym)
            stack.add(sym)
            for (ref in references[sym] ?: emptySet()) {
                visit(ref)
            }
            stack.remove(sym)
        }

        for (sym in references.keys) {
            visit(sym)
        }
    }


    private fun collectReferences(tree: ParseTree): Set<Symbol> {
        val found = mutableSetOf<Symbol>()
        fun visit(t: ParseTree) {
            when (t) {
                is ForgeParser.FunctionCallContext ->  if (t.symbol != null) found.add(t.symbol!!)
                is ForgeParser.MethodCallContext -> if (t.symbol != null) found.add(t.symbol!!)
                is ForgeParser.IdentifierContext -> if (t.symbol != null) found.add(t.symbol!!)
                is ForgeParser.FieldAccessContext -> if (t.symbol != null) found.add(t.symbol!!)
            }
            for (i in 0 until t.childCount) visit(t.getChild(i))
        }
        visit(tree)
        return found
    }

}
