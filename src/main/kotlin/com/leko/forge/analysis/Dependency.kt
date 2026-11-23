package com.leko.forge.analysis

import ForgeParser
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.symbol
import com.leko.forge.context.Context
import com.leko.forge.model.*
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
            // equations: on the node itself, or a default on one of its interfaces (also circular ones)
            val implementations = (listOf(concreteNode) + parents).flatMap { owner ->
                typeMap.nodeAttributes[owner].orEmpty().filter { (it is SynSymbol && it.hasBody) || it is CircularSymbol }
            }
            for (parent in parents) {
                val ifaceSyns = typeMap.nodeAttributes[parent]?.filterIsInstance<SynSymbol>()?.filter { it.isDefinition } ?: emptyList()
                for (decl in ifaceSyns) {
                    val found = implementations.find { decl.name == it.name }
                    if (found == null) {
                        log.diag(
                            missingSynAttributeImplementationFor(
                                decl.span,
                                concreteNode.name,
                                "${parent.name}.${decl.name}"
                            )
                        )
                    }
                    else {
                        chk.checkAssignable(decl.type, found.type)
                    }
                }
            }
        }
    }


    /**
     * Reports attributes and functions that depend on themselves. Cycles through a circular attribute are
     * allowed: they are what circular attributes are for.
     */
    private fun checkCyclicDependencies(root: ForgeParser.RootContext) {
        val references = mutableMapOf<Symbol, MutableSet<Symbol>>()

        fun add(sym: Symbol, tree: ParseTree) {
            references.getOrPut(sym) { mutableSetOf() } += collectReferences(tree)
        }

        for (synImpl in root.synDef()) {
            if (synImpl.syn == null) continue
            add(synImpl.symbol ?: continue, synImpl)
        }
        for (circular in root.circularDef()) {
            add(circular.symbol ?: continue, circular)
        }
        for (func in root.function()) {
            add(func.symbol ?: continue, func)
        }
        for (coll in root.coll()) {
            add(coll.symbol ?: continue, coll)
        }
        for (contrib in root.contrib()) {
            add(contrib.symbol ?: continue, contrib)
        }
        // constants are inlined: a function that uses a constant whose value calls it calls itself
        for (const in root.constDef()) {
            add(const.symbol ?: continue, const.value)
        }

        val visited = mutableSetOf<Symbol>()
        val stack = mutableListOf<Symbol>()
        val reported = mutableSetOf<Symbol>()

        fun visit(sym: Symbol) {
            val index = stack.indexOf(sym)
            if (index >= 0) {
                val cycle = stack.subList(index, stack.size)
                // cycles of constants alone were reported when they were attributed
                if (cycle.none { it is CircularSymbol } && !cycle.all { it is ConstSymbol } && reported.add(sym)) {
                    log.diag(cyclicDependencyDetected(sym.span, cycle.joinToString(" -> ") { it.name } + " -> " + sym.name))
                }
                return
            }
            if (sym in visited) return
            visited.add(sym)
            stack.add(sym)
            for (ref in references[sym] ?: emptySet()) {
                visit(ref)
            }
            stack.removeAt(stack.size - 1)
        }

        for (sym in references.keys.toList()) {
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
