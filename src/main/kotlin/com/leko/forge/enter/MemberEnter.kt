package com.leko.forge.enter

import ForgeParser
import com.leko.forge.analysis.Check
import com.leko.forge.analysis.abstractParseRuleAlreadyExist
import com.leko.forge.analysis.attributeAlreadyDefined
import com.leko.forge.analysis.canNotFindIdentifier
import com.leko.forge.analysis.interfaceNodeNotFound
import com.leko.forge.analysis.methodAlreadyExist
import com.leko.forge.analysis.nodeNotFound
import com.leko.forge.analysis.parseRuleAlreadyExist
import com.leko.forge.analysis.parserNotFound
import com.leko.forge.attr.Resolve
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.setSym
import com.leko.forge.attr.symbol
import com.leko.forge.context.Context
import com.leko.forge.analysis.*
import com.leko.forge.model.*
import com.leko.forge.model.CollSymbol
import com.leko.forge.model.ConcreteNodeSymbol
import com.leko.forge.model.ConcreteParserRuleSymbol
import com.leko.forge.model.FunctionSymbol
import com.leko.forge.model.InterfaceNodeSymbol
import com.leko.forge.model.InterfaceParserRuleSymbol
import com.leko.forge.model.SynSymbol
import com.leko.forge.model.VarSymbol
import com.leko.forge.model.span
import com.leko.forge.types.*
import com.leko.forge.parse.isStd
import com.leko.forge.util.Log


class MemberEnter private constructor(context: Context) {
    companion object {
        fun instance(context: Context): MemberEnter =
            context.getServices(MemberEnter::class) { MemberEnter(context) }
    }

    private val symTab = SymTab.instance(context)

    private val log = Log.instance(context)

    private val typeMap = TypeMap.instance(context)

    private val resolve = Resolve.instance(context)

    private val chk = Check.instance(context)


    fun enter(root: ForgeParser.RootContext) {
        enterInheritance(root)
        enterSyntheticAttributes(root)
        enterCircularAttributes(root)
        enterInheritedAttributes(root)
        enterCollectionAttributes(root)
        enterMethods(root)
        enterParserRules(root)
        enterFlows(root)
        checkNameClashes()
    }

    /** Fields and attributes become members of the same generated classes, so their names must differ. */
    private fun checkNameClashes() {
        for (node in symTab.concreteNodes.values) {
            val owners = listOf(node) + typeMap.allParentsOf(node)
            for (field in node.fields) {
                if (field.name in symTab.inheritedAttributes) {
                    log.diag(fieldClashesWithAttribute(field.span, node.name, field.name, "inherited attribute"))
                    continue
                }
                if (owners.any { owner -> typeMap.nodeAttributes[owner].orEmpty().any { it.name == field.name } }) {
                    log.diag(fieldClashesWithAttribute(field.span, node.name, field.name, "attribute"))
                }
            }
        }
    }

    private fun addParent(sym: NodeSymbol, parent: InterfaceNodeSymbol) {
        if (parent in sym.parents) return
        when (sym) {
            is InterfaceNodeSymbol -> (typeMap.ifaceParents.getOrPut(sym) { mutableListOf() } as MutableList) += parent
            is ConcreteNodeSymbol -> (typeMap.nodeIfaceImpls.getOrPut(sym) { mutableListOf() } as MutableList) += parent
        }
        sym.parents += parent
    }

    /** `node A: B`, `for A impl B`, and `Vertex` as the parent of every node without one. */
    private fun enterInheritance(root: ForgeParser.RootContext) {
        for (node in root.nodeDef()) {
            val sym = node.symbol ?: continue
            val parentName = node.parent?.text ?: continue
            val parentSym = symTab.interfaceNodes.values.find { it.name == parentName }
            if (parentSym != null) {
                addParent(sym, parentSym)
            }
            else {
                log.diag(interfaceNodeNotFound(node.parent.span, parentName))
            }
        }
        for (impl in root.nodeInterfaceImpl()) {
            val sym = symTab[impl.extended.text] as? NodeSymbol
            val parentSym = symTab.interfaceNodes.values.find { it.name == impl.iface.text }
            if (sym == null) {
                log.diag(nodeNotFound(impl.extended.span, impl.extended.text))
                continue
            }
            if (parentSym == null) {
                log.diag(interfaceNodeNotFound(impl.iface.span, impl.iface.text))
                continue
            }
            addParent(sym, parentSym)
        }
        val vertex = symTab[VERTEX] as InterfaceNodeSymbol
        for (node in root.nodeDef()) {
            val sym = node.symbol ?: continue
            if (sym.parents.isEmpty()) addParent(sym, vertex)
        }
    }

    private fun findNode(name: String): NodeSymbol? = symTab[name] as? NodeSymbol

    private fun enterSyntheticAttributes(root: ForgeParser.RootContext) {
        for (synDef in root.synDef()) {
            val nodeName = synDef.node.text
            val attrName = synDef.attrName.text
            val type = chk.checkExists(synDef.attrType.toType())

            // an equation can be given on any node (on an interface, it is the default of its subtypes);
            // a declaration without equation only on interfaces
            val nodeSym = if (synDef.syn == null) {
                symTab.interfaceNodes.values.find { it.name == nodeName }
            }
            else {
                findNode(nodeName)
            }

            if (nodeSym == null) {
                log.diag(if (synDef.syn == null) interfaceNodeNotFound(synDef.node.span, nodeName) else nodeNotFound(synDef.node.span, nodeName))
                continue
            }

            val synAttrsPerNode = typeMap.nodeAttributes.getOrPut(nodeSym) { mutableListOf() } as MutableList
            if (synAttrsPerNode.any { it.name == attrName }) {
                log.diag(attributeAlreadyDefined(synDef.attrName.span, nodeName, attrName))
                continue
            }
            val sym = SynSymbol(nodeSym, attrName, type, synDef.attrName.span, hasBody = synDef.syn != null)
            synAttrsPerNode += sym
            synDef.setSym(sym)
        }
    }

    private fun enterCircularAttributes(root: ForgeParser.RootContext) {
        for (circular in root.circularDef()) {
            val nodeName = circular.node.text
            val attrName = circular.attrName.text
            val type = chk.checkExists(circular.attrType.toType())
            val nodeSym = findNode(nodeName)

            if (nodeSym == null) {
                log.diag(nodeNotFound(circular.node.span, nodeName))
                continue
            }

            val attrsPerNode = typeMap.nodeAttributes.getOrPut(nodeSym) { mutableListOf() } as MutableList
            val existing = attrsPerNode.find { it.name == attrName }
            if (existing != null) {
                log.diag(
                    if (existing is SynSymbol) circularAndSynDefined(circular.attrName.span, nodeName, attrName)
                    else attributeAlreadyDefined(circular.attrName.span, nodeName, attrName)
                )
                continue
            }
            val sym = CircularSymbol(nodeSym, attrName, type, circular.attrName.span)
            attrsPerNode += sym
            circular.setSym(sym)
        }
    }

    private fun enterInheritedAttributes(root: ForgeParser.RootContext) {
        for (inhImpl in root.inhImpl()) {
            val nodeName = inhImpl.node.text
            val attrName = inhImpl.attrName.text
            val nodeSym = symTab.concreteNodes.values.find { it.name == nodeName }

            if (nodeSym == null) {
                log.diag(nodeNotFound(inhImpl.node.span, nodeName))
                continue
            }

            val synAttrsPerNode = typeMap.nodeAttributes.getOrPut(nodeSym) { mutableListOf() }
            if (synAttrsPerNode.any { it.name == attrName }) {
                log.diag(attributeAlreadyDefined(inhImpl.attrName.span, nodeName, attrName))
                continue
            }
        }
    }

    private fun enterFlows(root: ForgeParser.RootContext) {
        val seen = mutableSetOf<Pair<FlowGraphSymbol, NodeSymbol>>()
        for (flow in root.flowDef()) {
            val graph = symTab.graphs.values.find { it.name == flow.graph.text }
            if (graph == null) {
                log.diag(graphNotFound(flow.graph.span, flow.graph.text))
                continue
            }
            val node = findNode(flow.node.text)
            if (node == null) {
                log.diag(nodeNotFound(flow.node.span, flow.node.text))
                continue
            }
            if (!seen.add(graph to node)) {
                log.diag(flowAlreadyDefined(flow.node.span, graph.name, node.name))
                continue
            }
            val binders = flow.flowBinder()
            val fields = (node as? ConcreteNodeSymbol)?.fields ?: emptyList()
            if (binders.size > fields.size) {
                log.diag(tooManyFlowBinders(flow.node.span, node.name, fields.size))
            }
            flow.setSym(FlowSymbol(graph, node, flow.node.span))
        }
    }

    private fun enterCollectionAttributes(root: ForgeParser.RootContext) {
        for (coll in root.coll()) {
            val nodeName = coll.node.text
            val collName = coll.collName.text
            val type = chk.checkExists(coll.collType.toType())
            val nodeSym = symTab.concreteNodes.values.find { it.name == nodeName }

            if (nodeSym == null) {
                log.diag(nodeNotFound(coll.node.span, nodeName))
                continue
            }

            val collAttrsPerNode = typeMap.nodeAttributes.getOrPut(nodeSym) { mutableListOf() } as MutableList
            if (collAttrsPerNode.any { it.name == collName }) {
                log.diag(attributeAlreadyDefined(null, nodeName, collName))
                continue
            }
            val sym = CollSymbol(nodeSym, collName, type, coll.collName.span)
            collAttrsPerNode += sym
            coll.setSym(sym)
        }
    }


    /** The operators functions can define: name to number of parameters. */
    private val operators = mapOf(
        "plus" to 1, "minus" to 1, "times" to 1, "div" to 1, "rem" to 1, "concat" to 1,
        "shl" to 1, "shr" to 1, "ushr" to 1, "compareTo" to 1, "contains" to 1, "get" to 1, "slice" to 2,
        "neg" to 0, "not" to 0,
    )

    private fun checkOperator(func: ForgeParser.FunctionContext, sym: FunctionSymbol) {
        val arity = operators[sym.name]
        when {
            arity == null -> log.diag(invalidOperatorFunction(func.name.span,
                "'${sym.name}' is no operator; operators are ${operators.keys.joinToString(", ")}"))
            sym.params.size != arity -> log.diag(invalidOperatorFunction(func.name.span,
                "operator '${sym.name}' takes $arity parameter${if (arity == 1) "" else "s"}"))
            sym.name == "compareTo" && sym.returnType != IntType -> log.diag(invalidOperatorFunction(func.name.span, "operator 'compareTo' returns an int"))
            sym.name == "contains" && sym.returnType != BoolType -> log.diag(invalidOperatorFunction(func.name.span, "operator 'contains' returns a bool"))
            sym.name == "slice" && sym.params.any { it.type != OptionalType(IntType) && it.type !is TypeVar } ->
                log.diag(invalidOperatorFunction(func.name.span, "operator 'slice' takes two int? (an omitted bound is null)"))
        }
    }

/** How the targets name an `ext fun` of the standard library: `str.upper`, `List.indexOf`, `Map.keys`. */
    private fun builtinKey(receiver: Type, name: String): String = when (receiver) {
        is ListType -> "List"
        is SetType -> "Set"
        is MapType -> "Map"
        else -> receiver.toString()
    } + "." + name

        private fun enterMethods(root: ForgeParser.RootContext) {
        // the program's methods first: a method of the standard library with the same receiver and name gives way
        for (func in root.function().sortedBy { it.isStd() }) {
            if (func.op != null && func.op.text != "op") {
                log.diag(invalidOperatorFunction(func.op.span, "unexpected '${func.op.text}' before 'fun'"))
            }
            if (func.op != null && func.receiver == null) {
                log.diag(invalidOperatorFunction(func.name.span, "an operator function needs a receiver: op fun T.${func.name.text}(...)"))
            }
            val typeParams = func.typeParams.map { it.text }
            fun type(t: ForgeParser.TypeContext) = chk.checkExists(t.toType().withTypeParams(typeParams))
            val receiver = if (func.receiver != null) type(func.receiver!!) else continue
            val receiverSym = resolve.resolve(receiver)
            if (receiverSym != null) {
                // a generic method belongs to no receiver symbol: List<T> stands for every list
                val methods = if (typeParams.isEmpty()) typeMap.methods.getOrPut(receiverSym) { mutableListOf() } as MutableList
                    else typeMap.genericMethods
                val hidden = methods.any { it.name == func.name.text && (typeParams.isEmpty() || it.receiver?.type == receiver) }
                if (hidden && !func.isStd()) {
                    log.diag(methodAlreadyExist(null, "$receiver.${func.name.text}"))
                    continue
                }
                val sym = FunctionSymbol(
                    receiverSym,
                    func.name.text,
                    func.parameter().map { VarSymbol(it.name.text, type(it.ty), it.name.span, VarSymbol.VarKind.PARAMETER) },
                    type(func.ty),
                    func.name.span,
                    isOperator = func.op != null,
                    typeParams = typeParams,
                    builtin = if (func.EXT() != null && func.isStd()) builtinKey(receiver, func.name.text) else null,
                )
                if (func.op != null) checkOperator(func, sym)
                // hidden by one of the program: only the standard library itself uses it
                if (hidden) typeMap.stdMethods += sym else methods += sym
                func.setSym(sym)
            }
            /*
            else {
                // is either a primitive receiver or a List/Map
            }
            */

        }
    }

    private fun enterParserRules(root: ForgeParser.RootContext) {
        for (rule in root.parseRuleDef()) {
            if (rule.ARROW() != null) continue

            val parserName = rule.parserName.text
            val ruleName = rule.ruleName.text
            val returnType = chk.checkExists(rule.returnType.toType())

            val parser = symTab.parsers.values.firstOrNull { it.name == parserName }
            if (parser == null) {
                log.diag(parserNotFound(null, parserName))
                continue
            }

            val rules = typeMap.parserRuleDefs.getOrPut(parser) { mutableListOf() } as MutableList
            if (rules.any { it.name == ruleName }) {
                log.diag(abstractParseRuleAlreadyExist(null, "$parserName.$ruleName"))
                continue
            }
            val sym = InterfaceParserRuleSymbol(parser, ruleName, returnType, rule.ruleName.span)
            rules += sym
            rule.setSym(sym)
        }

        for (rule in root.parseRuleDef()) {
            if (rule.ARROW() == null) continue

            val parserName = rule.parserName.text
            val ruleName = rule.ruleName.text
            val returnType = chk.checkExists(rule.returnType.toType())

            val parser = symTab.parsers.values.find { it.name == parserName }
            if (parser == null) {
                log.diag(parserNotFound(null, parserName))
                continue
            }

            val rules = typeMap.parserRuleImpls.getOrPut(parser) { mutableListOf() } as MutableList
            if (!typeMap.parserRuleDefs.getOrDefault(parser, listOf()).any { it.name == ruleName }) {
                if (rules.any { it.name == ruleName }) {
                    log.diag(parseRuleAlreadyExist(null, "$parserName.$ruleName"))
                    continue
                }
            }
            val sym = ConcreteParserRuleSymbol(parser, ruleName, returnType, rule.ruleName.span)
            rules += sym
            rule.setSym(sym)
        }
    }

}
