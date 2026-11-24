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
        checkDeclaredTypes(root)
        enterInheritance(root)
        enterSyntheticAttributes(root)
        enterCircularAttributes(root)
        enterInheritedAttributes(root)
        enterCollectionAttributes(root)
        enterMethods(root)
        enterParserRules(root)
        enterFlows(root)
        checkTraitMembers(root)
        checkNameClashes()
    }

    /**
     * The types of the fields of nodes and records, of the parameters of enum constants, of inherited attributes
     * and of constants must exist; Enter, which enters the types, takes them as they are written.
     */
    private fun checkDeclaredTypes(root: ForgeParser.RootContext) {
        val types = root.nodeDef().flatMap { node -> node.parameter().map { it.ty to emptyList<String>() } } +
            root.record().flatMap { record -> record.recordField().map { it.ty to record.typeParams().names() } } +
            root.enum_().flatMap { enum -> enum.enumItem().flatMap { item -> item.parameter().map { it.ty to enum.typeParams().names() } } } +
            root.inhDef().map { it.attrType to emptyList() } +
            root.constDef().mapNotNull { it.ty?.let { t -> t to emptyList() } } +
            root.traitDef().flatMap { t -> t.traitMember().flatMap { m -> m.parameter().map { it.ty } + m.ty }.map { it to listOf(SELF) } } +
            // the types of top level functions (those of methods are checked where they are entered)
            root.function().filter { it.receiver == null && it.property == null }.flatMap { f ->
                (f.parameter().map { it.ty } + f.ty).map { it to f.typeParams().names() }
            }
        for ((type, params) in types) {
            if (type != null) chk.checkExists(type.toType().withTypeParams(params), type)
        }
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
            val type = chk.checkExists(synDef.attrType.toType(), synDef.attrType)

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
            val type = chk.checkExists(circular.attrType.toType(), circular.attrType)
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
            val type = chk.checkExists(coll.collType.toType(), coll.collType)
            val nodeSym = symTab.concreteNodes.values.find { it.name == nodeName }

            if (nodeSym == null) {
                log.diag(nodeNotFound(coll.node.span, nodeName))
                continue
            }

            val collAttrsPerNode = typeMap.nodeAttributes.getOrPut(nodeSym) { mutableListOf() } as MutableList
            if (collAttrsPerNode.any { it.name == collName }) {
                log.diag(attributeAlreadyDefined(coll.collName.span, nodeName, collName))
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

    private fun checkOperator(func: ForgeParser.FunctionContext, sym: FunctionSymbol) =
        checkOperator(func.name.span, sym.name, sym.params.map { it.type }, sym.returnType)

    /**
     * What an operator function must be: its name one of an operator, with its number of parameters; the other
     * types are free (so traits can have operators of different signatures), except where the operator needs them.
     */
    private fun checkOperator(span: SourceSpan, name: String, params: List<Type>, returnType: Type) {
        val arity = operators[name]
        when {
            arity == null -> log.diag(invalidOperatorFunction(span,
                "'$name' is no operator; operators are ${operators.keys.joinToString(", ")}"))
            params.size != arity -> log.diag(invalidOperatorFunction(span,
                "operator '$name' takes $arity parameter${if (arity == 1) "" else "s"}"))
            name == "compareTo" && returnType != IntType -> log.diag(invalidOperatorFunction(span, "operator 'compareTo' returns an int"))
            name == "contains" && returnType != BoolType -> log.diag(invalidOperatorFunction(span, "operator 'contains' returns a bool"))
            name == "slice" && params.any { it != OptionalType(IntType) && it !is TypeVar } ->
                log.diag(invalidOperatorFunction(span, "operator 'slice' takes two int? (an omitted bound is null)"))
        }
    }

    /** `op fun` members of traits are operators: the same rules as for operator functions. */
    private fun checkTraitMembers(root: ForgeParser.RootContext) {
        for (trait in root.traitDef()) {
            val sym = symTab.traits[trait.name.text] ?: continue
            for ((member, ctx) in sym.members.zip(trait.traitMember())) {
                val op = ctx.op ?: continue
                when {
                    op.text != "op" -> log.diag(invalidOperatorFunction(op.span, "unexpected '${op.text}' before 'fun'"))
                    member.isProperty -> log.diag(invalidOperatorFunction(op.span, "an operator is a fun, not a val"))
                    else -> checkOperator(member.span, member.name, member.params.map { it.type }, member.returnType)
                }
            }
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
            val property = func.property != null
            if (property && func.params != null) {
                log.diag(invalidProperty(func.params.span, "a val has no parameters: val T.${func.name.text}: ${func.ty.text}"))
            }
            if (!property && func.params == null) {
                log.diag(invalidProperty(func.name.span, "a fun needs parameters: fun ${func.name.text}(): ${func.ty.text}, or a val"))
            }
            if (property && func.op != null) {
                log.diag(invalidOperatorFunction(func.op.span, "an operator is a fun, not a val"))
            }
            if (property && func.receiver == null) {
                log.diag(invalidProperty(func.name.span, "a val needs a receiver: val T.${func.name.text}: ${func.ty.text}"))
            }
            val typeParams = func.typeParams().names()
            fun type(t: ForgeParser.TypeContext) = chk.checkExists(t.toType().withTypeParams(typeParams), t)
            val receiver = if (func.receiver != null) type(func.receiver!!) else continue
            val receiverSym = resolve.resolve(receiver)
            if (property && receiverSym is NodeSymbol) {
                log.diag(invalidProperty(func.name.span, "a node has attributes instead of vals: syn ${func.receiver.text}.${func.name.text}: ${func.ty.text}"))
                continue
            }
            if (property && receiverSym is RecordSymbol && receiverSym.fields.any { it.name == func.name.text }) {
                log.diag(invalidProperty(func.name.span, "'${func.name.text}' is a field of $receiver"))
                continue
            }
            if (receiverSym != null) {
                // a generic method belongs to no receiver symbol: List<T> stands for every list
                val methods = if (typeParams.isEmpty()) typeMap.methods.getOrPut(receiverSym) { mutableListOf() } as MutableList
                    else typeMap.genericMethods
                val hidden = methods.any { it.name == func.name.text && (typeParams.isEmpty() || it.receiver?.type == receiver) }
                if (hidden && !func.isStd()) {
                    log.diag(methodAlreadyExist(func.name.span, "$receiver.${func.name.text}"))
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
                    isProperty = property,
                    builtin = if (func.EXT() != null && func.isStd()) builtinKey(receiver, func.name.text) else null,
                    bounds = func.typeParams().bounds(),
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
            val returnType = chk.checkExists(rule.returnType.toType(), rule.returnType)

            val parser = symTab.parsers.values.firstOrNull { it.name == parserName }
            if (parser == null) {
                log.diag(parserNotFound(rule.parserName.span, parserName))
                continue
            }

            val rules = typeMap.parserRuleDefs.getOrPut(parser) { mutableListOf() } as MutableList
            if (rules.any { it.name == ruleName }) {
                log.diag(abstractParseRuleAlreadyExist(rule.ruleName.span, "$parserName.$ruleName"))
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
            val returnType = chk.checkExists(rule.returnType.toType(), rule.returnType)

            val parser = symTab.parsers.values.find { it.name == parserName }
            if (parser == null) {
                log.diag(parserNotFound(rule.parserName.span, parserName))
                continue
            }

            val rules = typeMap.parserRuleImpls.getOrPut(parser) { mutableListOf() } as MutableList
            if (!typeMap.parserRuleDefs.getOrDefault(parser, listOf()).any { it.name == ruleName }) {
                if (rules.any { it.name == ruleName }) {
                    log.diag(parseRuleAlreadyExist(rule.ruleName.span, "$parserName.$ruleName"))
                    continue
                }
            }
            val sym = ConcreteParserRuleSymbol(parser, ruleName, returnType, rule.ruleName.span)
            rules += sym
            rule.setSym(sym)
        }
    }

}
