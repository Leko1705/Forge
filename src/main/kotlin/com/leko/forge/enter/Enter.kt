package com.leko.forge.enter

import ForgeParser
import com.leko.forge.analysis.*
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.setSym
import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.toType
import com.leko.forge.util.Log


class Enter private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Enter =
            context.getServices(Enter::class) { Enter(context) }
    }

    private val symTab = SymTab.instance(context)

    private val log = Log.instance(context)

    fun enter(root: ForgeParser.RootContext) {
        for (node in root.nodeDef()) {
            if (node.LPAREN() == null) {
                if (node.name.text in symTab) {
                    log.diag(interfaceAlreadyExist(node.name.span, node.name.text))
                    continue
                }
                val sym = InterfaceNodeSymbol(
                    node.name.text,
                    mutableListOf(),
                    node.name.span,
                )
                symTab[sym.type] = sym
                node.setSym(sym)
            }
            else {
                if (node.name.text in symTab) {
                    log.diag(nodeAlreadyExist(node.name.span, node.name.text))
                    continue
                }
                val sym = ConcreteNodeSymbol(
                    node.name.text,
                    node.parameter().map { VarSymbol(it.name.text, it.ty.toType(), it.name.span, VarSymbol.VarKind.NODE_FIELD) },
                    mutableListOf(),
                    node.name.span,
                )
                symTab[sym.type] = sym
                node.setSym(sym)
            }
        }
        for (enum in root.enum_()) {
            if (enum.name.text in symTab) {
                log.diag(enumAlreadyExist(enum.name.span, enum.name.text))
                continue
            }
            val sym = EnumSymbol(
                enum.name.text,
                enum.enumItem().map { item ->
                    EnumItemSymbol(
                        item.name.text,
                        enum.name.text,
                        item.parameter().map { it.name.text to it.ty.toType() },
                        item.name.span,
                        )
                },
                enum.name.span,
            )
            symTab[sym.type] = sym
            enum.setSym(sym)
        }
        for (record in root.record()) {
            if (record.name.text in symTab) {
                log.diag(recordAlreadyExist(record.name.span, record.name.text))
                continue
            }
            val sym = RecordSymbol(
                record.name.text,
                record.recordField().map { VarSymbol(it.name.text, it.ty.toType(), it.name.span, VarSymbol.VarKind.RECORD_FIELD) },
                record.name.span,
            )
            symTab[sym.type] = sym
            record.setSym(sym)
        }
        for (func in root.function()) {
            if (func.receiver != null) continue
            if (func.name.text in symTab) {
                log.diag(functionAlreadyExist(func.name.span, func.name.text))
                continue
            }
            val sym = FunctionSymbol(
                null,
                func.name.text,
                func.parameter().map { VarSymbol(it.name.text, it.ty.toType(), it.name.span, VarSymbol.VarKind.PARAMETER) },
                func.ty.toType(),
                func.name.span,
            )
            symTab[sym.type] = sym
            func.setSym(sym)
        }
        for (inhDef in root.inhDef()) {
            val attrName = inhDef.attrName.text
            val attrType = inhDef.attrType.toType()
            if (attrName in symTab.inheritedAttributes) {
                log.diag(inheritedAttributeAlreadyExist(inhDef.attrName.span, attrName))
                continue
            }
            val sym = InhSymbol(attrName, attrType, inhDef.attrName.span)
            symTab.inheritedAttributes[attrName] = sym
            inhDef.setSym(sym)
        }
        for (parser in root.parserDef()) {
            val name = parser.name.text
            if (name in symTab) {
                log.diag(parserAlreadyExist(parser.name.span, name))
                continue
            }
            val sym = ParserSymbol(name, parser.name.span)
            symTab[sym.type] = sym
            parser.setSym(sym)
        }
    }
}
