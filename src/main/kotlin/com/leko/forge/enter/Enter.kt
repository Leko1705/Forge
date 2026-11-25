package com.leko.forge.enter

import ForgeParser
import com.leko.forge.analysis.*
import com.leko.forge.attr.Builtins
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.setSym
import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.toType
import com.leko.forge.types.withTypeParams
import com.leko.forge.types.ComplexType
import com.leko.forge.types.TypeVar
import com.leko.forge.types.Type
import com.leko.forge.parse.isStd
import com.leko.forge.util.Log


class Enter private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Enter =
            context.getServices(Enter::class) { Enter(context) }
    }

    private val symTab = SymTab.instance(context)

    private val log = Log.instance(context)

    private val typeMap = TypeMap.instance(context)

    fun enter(root: ForgeParser.RootContext) {
        // `fun show(x: Printable)` is `fun <T: Printable> show(x: T)`
        desugarTraitParameters(root)
        Builtins.enter(symTab, typeMap)
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
            val typeParams = enum.typeParams().names()
            val sym = EnumSymbol(
                enum.name.text,
                enum.enumItem().map { item ->
                    EnumItemSymbol(
                        item.name.text,
                        enum.name.text,
                        item.parameter().map { it.name.text to it.ty.toType().withTypeParams(typeParams) },
                        item.name.span,
                        typeParams,
                    )
                },
                enum.name.span,
                typeParams,
            )
            symTab[sym.type] = sym
            enum.setSym(sym)
        }
        for (record in root.record()) {
            if (record.name.text in symTab) {
                log.diag(recordAlreadyExist(record.name.span, record.name.text))
                continue
            }
            val typeParams = record.typeParams().names()
            val sym = RecordSymbol(
                record.name.text,
                record.recordField().map { VarSymbol(it.name.text, it.ty.toType().withTypeParams(typeParams), it.name.span, VarSymbol.VarKind.RECORD_FIELD) },
                record.name.span,
                type = ComplexType(record.name.text, typeParams.map { TypeVar(it) }),
                typeParams = typeParams,
            )
            symTab[sym.type] = sym
            record.setSym(sym)
        }
        // the program's functions first: a function of the standard library with the same name gives way
        for (func in root.function().sortedBy { it.isStd() }) {
            if (func.receiver != null || func.property != null) continue
            val hidden = func.name.text in symTab
            if (hidden && !func.isStd()) {
                log.diag(functionAlreadyExist(func.name.span, func.name.text))
                continue
            }
            val typeParams = func.typeParams().names()
            val sym = FunctionSymbol(
                null,
                func.name.text,
                func.parameter().map { VarSymbol(it.name.text, it.ty.toType().withTypeParams(typeParams), it.name.span, VarSymbol.VarKind.PARAMETER) },
                func.ty.toType().withTypeParams(typeParams),
                func.name.span,
                typeParams = typeParams,
                builtin = if (func.EXT() != null && func.isStd()) func.name.text else null,
                bounds = func.typeParams().bounds(),
            )
            // hidden by one of the program: only the standard library itself uses it
            if (hidden) symTab.stdFunctions[sym.name] = sym else symTab.functions[sym.name] = sym
            func.setSym(sym)
        }
        for (trait in root.traitDef()) {
            val name = trait.name.text
            if (name in symTab) {
                log.diag(traitAlreadyExist(trait.name.span, name))
                continue
            }
            val members = trait.traitMember().map { member ->
                fun type(t: ForgeParser.TypeContext) = t.toType().withTypeParams(listOf(SELF))
                TraitMember(
                    member.name.text,
                    member.parameter().map { VarSymbol(it.name.text, type(it.ty), it.name.span, VarSymbol.VarKind.PARAMETER) },
                    type(member.ty),
                    member.property != null,
                    member.name.span,
                    isOperator = member.op != null,
                )
            }
            val sym = TraitSymbol(name, members, trait.name.span)
            symTab[sym.type] = sym
            symTab.traits[name] = sym
            trait.setSym(sym)
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
            var binary = false
            var skip: String? = null
            var skipSet = false
            val keywords = mutableListOf<String>()
            for (option in parser.parserOption()) {
                val values = option.REGEX_STRING().map { it.text.substring(1, it.text.length - 1) }
                when (option.key.text) {
                    "binary" -> {
                        if (values.isNotEmpty()) log.diag(invalidParserOption(option.key.span, "'binary' takes no value"))
                        binary = true
                    }
                    "skip" -> {
                        if (values.size != 1) log.diag(invalidParserOption(option.key.span, "'skip' takes one regular expression, '' to skip nothing"))
                        skip = values.firstOrNull()?.takeIf { it.isNotEmpty() }
                        skipSet = true
                    }
                    "keywords" -> keywords += values
                    else -> log.diag(invalidParserOption(option.key.span, "unknown option '${option.key.text}'; expected binary, skip or keywords"))
                }
            }
            for (regex in listOfNotNull(skip)) {
                try { java.util.regex.Pattern.compile(regex) } catch (e: java.util.regex.PatternSyntaxException) {
                    log.diag(invalidRegex(parser.name.span, regex, e.description))
                }
            }
            val sym = ParserSymbol(
                name, parser.name.span, binary,
                if (skipSet) skip else if (binary) null else "\\s+",
                keywords,
            )
            symTab[sym.type] = sym
            parser.setSym(sym)
        }
        for (portDef in root.portDef()) {
            for (ident in portDef.IDENT()) {
                val name = ident.text
                if (name in symTab.ports) {
                    log.diag(portAlreadyExist(ident.span, name))
                    continue
                }
                symTab.ports[name] = PortSymbol(name, portDef.inherited != null, ident.span)
            }
        }
        for (const in root.constDef()) {
            val name = const.name.text
            if (name in symTab || name in symTab.constants || name in symTab.ports || name in symTab.inheritedAttributes) {
                log.diag(constantAlreadyExist(const.name.span, name))
                continue
            }
            val sym = ConstSymbol(name, const.ty?.toType(), const.name.span)
            symTab.constants[name] = sym
            const.setSym(sym)
        }
        for (graph in root.graphDef()) {
            val name = graph.name.text
            if (name in symTab) {
                log.diag(graphAlreadyExist(graph.name.span, name))
                continue
            }
            val rootSym = symTab[graph.rootNode.text] as? NodeSymbol
            if (rootSym == null) {
                log.diag(nodeNotFound(graph.rootNode.span, graph.rootNode.text))
                continue
            }
            val sym = FlowGraphSymbol(name, rootSym, graph.name.span)
            symTab[sym.type] = sym
            graph.setSym(sym)
        }
    }
}

/** The names of type parameters: `<A, B: Ord>` is `[A, B]`. */
fun ForgeParser.TypeParamsContext?.names(): List<String> = this?.typeParam()?.map { it.name.text }.orEmpty()

/** The bounds of type parameters, as written (type parameters among them are type variables). */
fun ForgeParser.TypeParamsContext?.bounds(): Map<String, List<Type>> {
    val names = names()
    return this?.typeParam()?.filter { it.bounds.isNotEmpty() }?.associate { p -> p.name.text to p.bounds.map { it.toType().withTypeParams(names) } }.orEmpty()
}
