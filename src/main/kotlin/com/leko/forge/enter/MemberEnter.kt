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
import com.leko.forge.analysis.cyclicDependencyDetected
import com.leko.forge.analysis.missingSynAttributeImplementationFor
import com.leko.forge.model.ParserRuleSymbol
import com.leko.forge.attr.Resolve
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.setSym
import com.leko.forge.attr.symbol
import com.leko.forge.context.Context
import com.leko.forge.model.CollSymbol
import com.leko.forge.model.ConcreteNodeSymbol
import com.leko.forge.model.ConcreteParserRuleSymbol
import com.leko.forge.model.FunctionSymbol
import com.leko.forge.model.InterfaceNodeSymbol
import com.leko.forge.model.InterfaceParserRuleSymbol
import com.leko.forge.model.SynSymbol
import com.leko.forge.model.VarSymbol
import com.leko.forge.model.span
import com.leko.forge.types.toType
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
        enterParserInheritance(root)
        enterInterfaceInheritance(root)
        enterConcreteInheritance(root)
        enterSyntheticAttributes(root)
        enterInheritedAttributes(root)
        enterCollectionAttributes(root)
        enterMethods(root)
        enterParserRules(root)
    }

    private fun enterParserInheritance(root: ForgeParser.RootContext) {
        for (parser in root.parserDef()) {
            val sym = symTab.parsers.values.find { it.name == parser.name.text } ?: continue
            val parentName = parser.parent?.text ?: continue
            val parentSym = symTab.parsers.values.find { it.name == parentName }
            if (parentSym == null) {
                log.diag(parserNotFound(parser.parent.span, parentName))
                continue
            }
            sym.parent = parentSym
            if (parentSym.lineage.size >= 64 || parentSym.lineage.contains(sym)) {
                log.diag(cyclicDependencyDetected(parser.parent.span, sym.name))
                sym.parent = null
            }
        }
    }

    private fun enterInterfaceInheritance(root: ForgeParser.RootContext) {
        for (node in root.nodeDef()) {
            val sym = node.symbol as? InterfaceNodeSymbol ?: continue
            val parentName = node.parent?.text ?: continue

            val parentSym = symTab.interfaceNodes.values.find { it.name == parentName }
            if (parentSym != null) {
                val ifaces = typeMap.ifaceParents.getOrPut(sym) { mutableListOf() } as MutableList
                ifaces.add(parentSym)
                sym.parents.addAll(ifaces)
            }
            else {
                log.diag(interfaceNodeNotFound(node.parent.span, parentName))
            }
        }
    }

    private fun enterConcreteInheritance(root: ForgeParser.RootContext) {
        for (node in root.nodeDef()) {
            val sym = node.symbol as? ConcreteNodeSymbol ?: continue
            val parentName = node.parent?.text ?: continue

            val parentSym = symTab.interfaceNodes.values.find { it.name == parentName }
            if (parentSym != null) {
                val ifaces = typeMap.nodeIfaceImpls.getOrPut(sym) { mutableListOf() } as MutableList
                ifaces.add(parentSym)
                sym.parents.addAll(ifaces)
            }
            else {
                log.diag(interfaceNodeNotFound(node.parent.span, parentName))
            }
        }
    }

    private fun enterSyntheticAttributes(root: ForgeParser.RootContext) {
        for (synIfaceDef in root.synDef()) {
            val nodeName = synIfaceDef.node.text
            val attrName = synIfaceDef.attrName.text
            val type = chk.checkExists(synIfaceDef.attrType.toType())

            val nodeSym = symTab.interfaceNodes.values.find { it.name == nodeName }
                ?: symTab.concreteNodes.values.find { it.name == nodeName }

            if (nodeSym == null) {
                log.diag(nodeNotFound(synIfaceDef.node.span, nodeName))
                continue
            }
            if (nodeSym is ConcreteNodeSymbol && synIfaceDef.syn == null) {
                log.diag(missingSynAttributeImplementationFor(synIfaceDef.attrName.span, nodeName, "$nodeName.$attrName"))
                continue
            }

            val synAttrsPerNode = typeMap.nodeAttributes.getOrPut(nodeSym) { mutableListOf() } as MutableList
            if (synAttrsPerNode.any { it.name == attrName }) {
                log.diag(attributeAlreadyDefined(synIfaceDef.attrName.span, nodeName, attrName))
                continue
            }
            val sym = SynSymbol(nodeSym, attrName, type, synIfaceDef.attrName.span, synIfaceDef.syn != null)
            synAttrsPerNode += sym
            synIfaceDef.setSym(sym)
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


    private fun enterMethods(root: ForgeParser.RootContext) {
        for (func in root.function()) {
            val receiver = if (func.receiver != null) chk.checkExists(func.receiver!!.toType()) else continue
            val receiverSym = resolve.resolve(receiver)
            if (receiverSym != null) {
                val methods = typeMap.methods.getOrPut(receiverSym) { mutableListOf() } as MutableList
                if (methods.any { it.name == func.name.text }) {
                    log.diag(methodAlreadyExist(null, "$receiver.${func.name.text}"))
                    continue
                }
                val sym = FunctionSymbol(
                    receiverSym,
                    func.name.text,
                    func.parameter().map { VarSymbol(it.name.text, chk.checkExists(it.ty.toType()), it.name.span) },
                    chk.checkExists(func.ty.toType()),
                    func.name.span
                )
                methods += sym
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
            val returnType = chk.checkExists(rule.returnType.toType())

            val parser = symTab.parsers.values.find { it.name == parserName }
            if (parser == null) {
                log.diag(parserNotFound(rule.parserName.span, parserName))
                continue
            }

            // A rule may be defined several times (each definition adds alternatives) if there is an
            // abstract declaration of it, or if it is inherited from a parent parser.
            val declared = resolve.resolveParserRule(parser, ruleName)
            val ownDefinitions = typeMap.parserRuleImpls[parser].orEmpty().filter { it.name == ruleName }
            val inherited = declared != null && declared !is InterfaceParserRuleSymbol &&
                    (declared as ConcreteParserRuleSymbol).parser != parser
            if (declared is ConcreteParserRuleSymbol && !inherited && ownDefinitions.isNotEmpty()) {
                log.diag(parseRuleAlreadyExist(rule.ruleName.span, "$parserName.$ruleName"))
                continue
            }
            if (declared is ParserRuleSymbol) {
                chk.checkAssignable(declared.returnType, returnType, rule.returnType.span)
            }

            val rules = typeMap.parserRuleImpls.getOrPut(parser) { mutableListOf() } as MutableList
            val sym = ConcreteParserRuleSymbol(parser, ruleName, returnType, rule.ruleName.span)
            rules += sym
            rule.setSym(sym)
        }
    }

}
