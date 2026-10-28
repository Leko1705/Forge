package com.leko.forge.attr

import ForgeParser
import ForgeParserBaseVisitor
import com.leko.forge.analysis.*
import com.leko.forge.context.Context
import com.leko.forge.lr.terminalSyntax
import com.leko.forge.model.*
import com.leko.forge.types.*
import com.leko.forge.util.Log
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ErrorNode
import java.util.regex.Pattern

class Attr private constructor(context: Context) {
    companion object {

        fun instance(context: Context): Attr =
            context.getServices(Attr::class) { Attr(context) }
    }

    private data class AttributeContext(
        val scope: Scope,
        val receiver: Type? = null,
        val expected: Type? = null,
        val matched: Type? = null,
        /** the parser whose rules are attributed */
        val parser: ParserSymbol? = null,
    )


    val chk = Check.instance(context)

    val resolve = Resolve.instance(context)

    val symTab = SymTab.instance(context)

    val types = Types.instance(context)

    val log = Log.instance(context)


    fun attr(root: ForgeParser.RootContext) {
        root.accept(TypeAttrib())
    }

    private inner class TypeAttrib : ForgeParserBaseVisitor<Type>() {

        private var env: Env<AttributeContext> = Env(AttributeContext(Scope()))

        private fun ParserRuleContext.attribType(): Type {
            return accept(this@TypeAttrib) ?: ErrorType
        }

        /** Looks [name] up in the local scopes, innermost first. */
        private fun lookup(name: String): Symbol? {
            var e: Env<AttributeContext>? = env
            while (e != null) {
                e.value.scope.entries[name]?.let { return it }
                e = e.next
            }
            return null
        }

        private fun attribIdent(ctx: ForgeParser.IdentifierContext, accepted: Set<Kind>): Type {
            val name = ctx.IDENT().text

            val sym = lookup(name) ?: resolve.resolve(name)

            if (sym == null) {
                log.diag(canNotFindIdentifier(ctx.IDENT().span, name))
                return ctx.setType(ErrorType)
            }

            ctx.setSym(sym)
            if (sym.kind !in accepted) {
                log.diag(invalidSymbolKind(ctx.IDENT().span, sym.kind, accepted))
                return ctx.setType(ErrorType)
            }
            return ctx.setType(sym.type)
        }

        private fun ForgeParser.ExpressionContext.attribOwner(accepted: Set<Kind>): Type = when (this) {
            is ForgeParser.IdentifierContext -> attribIdent(this, accepted)
            else -> attribType()
        }

        /** How a receiver expression is used: as a type (`Enum.ITEM`, `Parser.rule(..)`) or as a value. */
        private fun receiverKind(recv: ForgeParser.ExpressionContext): Kind =
            if (recv is ForgeParser.IdentifierContext && recv.symbol?.kind == Kind.TYP) Kind.TYP else Kind.VAL

        private inline fun <T> withExpected(expected: Type?, block: () -> T): T {
            val env = this.env
            this.env = env.copy(value = env.value.copy(expected = expected))
            val res = block()
            this.env = env
            return res
        }

        private inline fun <T> withMatched(matched: Type, block: () -> T): T {
            val env = this.env
            this.env = env.copy(value = env.value.copy(matched = matched))
            val res = block()
            this.env = env
            return res
        }

        private inline fun withReceiver(receiver: Type?, block: () -> Unit) {
            val prev = this.env
            this.env = prev.copy(value = prev.value.copy(receiver = receiver))
            block()
            this.env = prev
        }

        private inline fun <T> withChildScope(block: () -> T): T {
            val env = this.env
            this.env = env.child(env.value.copy(scope = Scope()))
            val res = block()
            this.env = env
            return res
        }

        private fun resolveMember(owner: Type, member: String, receiverKind: Kind, memberKind: Kind, span: SourceSpan? = null): Symbol? {
            val owner = if (owner is OptionalType) {
                chk.checkAssignable(owner.elem, owner, span)  // trigger type error
                owner.elem
            }
            else {
                owner
            }
            return resolve.resolve(owner, member, receiverKind, memberKind)
        }

        /** Attributes call arguments against the parameter types, so that e.g. `[]` gets its element type. */
        private fun attribArguments(args: List<ForgeParser.ExpressionContext>, params: List<Type>, span: SourceSpan?) {
            for ((i, arg) in args.withIndex()) {
                val expected = params.getOrNull(i)
                val got = withExpected(expected) { arg.attribType() }
                if (expected != null) chk.checkAssignable(expected, got, arg.span)
            }
            if (args.size < params.size) {
                log.diag(tooFewArguments(span))
            }
            if (args.size > params.size) {
                log.diag(tooManyArguments(span))
            }
        }

        override fun visitSynDef(ctx: ForgeParser.SynDefContext): Type? {
            val attrSym = ctx.symbol ?: return null

            if (ctx.syn != null) {
                withExpected(attrSym.type) {
                    withReceiver(attrSym.owner.type) {
                        val synTy = ctx.syn.attribType()
                        chk.checkAssignable(attrSym.type, synTy, ctx.syn.span)
                    }
                }
            }
            return null
        }

        override fun visitInhDef(ctx: ForgeParser.InhDefContext): Type? {
            val sym = ctx.symbol ?: return null
            return withExpected(sym.type) { chk.checkAssignable(sym.type, ctx.init.attribType(), ctx.init.span) }
        }

        override fun visitInhImpl(ctx: ForgeParser.InhImplContext): Type? {
            val nodeName = ctx.node.text
            val attrName = ctx.attrName.text
            val nodeSym = symTab.concreteNodes.values.find { it.name == nodeName } ?: return null
            val attrSym = symTab.inheritedAttributes[attrName]

            if (attrSym == null) {
                log.diag(syntheticRootAttributeNotFound(ctx.attrName.span, attrName))
            }

            withExpected(attrSym?.type ?: ErrorType) {
                withReceiver(nodeSym.type) {
                    ctx.inhDelegate().forEach { it.accept(this) }
                }
            }

            if (attrSym != null) {
                ctx.setSym(attrSym)
            }
            return null
        }

        override fun visitColl(ctx: ForgeParser.CollContext): Type? {
            val attrSym = ctx.symbol ?: return null

            withExpected(attrSym.type) {
                withReceiver(attrSym.owner.type) {
                    val synTy = ctx.init.attribType()
                    chk.checkAssignable(attrSym.type, synTy, ctx.init.span)
                }
            }

            return null
        }

        override fun visitContrib(ctx: ForgeParser.ContribContext): Type? {
            val nodeName = ctx.node.text
            val attrName = ctx.collName.text
            val nodeSym = symTab.concreteNodes.values.find { it.name == nodeName }
                ?: symTab.interfaceNodes.values.find { it.name == nodeName }
            if (nodeSym == null) {
                log.diag(nodeNotFound(ctx.node.span, nodeName))
                return null
            }
            val alias = ctx.alias?.text ?: attrName

            withReceiver(nodeSym.type) {
                val recvType = ctx.receiver.attribType()
                var collSym = if (recvType == ErrorType) null else resolveMember(recvType, attrName, Kind.VAL, Kind.VAL, ctx.receiver.span)

                if (collSym == null) {
                    if (recvType != ErrorType) log.diag(collectionAttributeNotFound(ctx.collName.span, recvType.toString(), attrName))
                }
                else if (collSym !is CollSymbol) {
                    log.diag(collectionAttributeExpected(ctx.collName.span))
                    collSym = null
                }

                if (collSym != null) {
                    withExpected(collSym.type) {
                        withChildScope {
                            env.value.scope.enter(VarSymbol(alias, collSym.type, ctx.alias?.span ?: ctx.collName.span))
                            val valueType = ctx.value.attribType()
                            chk.checkAssignable(collSym.type, valueType, ctx.value.span)
                        }
                    }
                    ctx.setSym(collSym)
                }
                else {
                    withChildScope {
                        env.value.scope.enter(VarSymbol(alias, ErrorType, ctx.alias?.span))
                        ctx.value.attribType()
                    }
                }
            }

            return null
        }

        override fun visitInhDelegate(ctx: ForgeParser.InhDelegateContext): Type? {
            val childName = ctx.child.text
            val key = ctx.key
            val value = ctx.value

            val childSym = resolveMember(env.value.receiver!!, childName, Kind.VAL, Kind.VAL)
            if (childSym == null || !(childSym is VarSymbol && childSym.varKind == VarSymbol.VarKind.NODE_FIELD)) {
                log.diag(canNotFindIdentifier(ctx.child.span, childName))
                return null
            }

            if (key == null) {
                if (childSym.type is ListType) {
                    log.diag(invalidToListDelegation(ctx.span, childName))
                }
                chk.checkAssignable(env.value.expected!!, value.attribType(), value.span)
            }
            else {
                withChildScope {
                    if (childSym.type !is ListType) {
                        log.diag(invalidListAccessDelegation(ctx.span, childName))
                    }
                    // `child[i] <- ..` introduces the index variable `i`, any other key is an int expression
                    val name = (key as? ForgeParser.IdentifierContext)?.IDENT()?.text
                    if (name != null && lookup(name) == null && resolve.resolve(name) == null) {
                        val binder = VarSymbol(name, IntType, key.span)
                        env.value.scope.enter(binder)
                        key.setSym(binder)
                        key.setType(IntType)
                        ctx.attach("binder", binder)
                    }
                    else {
                        chk.checkAssignable(IntType, key.attribType(), key.span)
                    }
                    chk.checkAssignable(env.value.expected!!, value.attribType(), value.span)
                }
            }

            return null
        }

        override fun visitFunction(ctx: ForgeParser.FunctionContext): Type? {
            val expr = ctx.expression()

            val sym = ctx.symbol ?: return null

            if (expr != null) {
                withChildScope {

                    sym.params.forEach { env.value.scope.enter(it) }

                    if (ctx.EXTERNAL() != null) {
                        log.diag(externalFunctionMustHaveNoBody(ctx.expression().span))
                    }
                    // local functions (`.. where fun f() = ..`) see the `this` of their context
                    val receiver = sym.receiver?.type ?: if (ctx.parent is ForgeParser.ContextualizedContext) env.value.receiver else null
                    withExpected(sym.returnType) {
                        withReceiver(receiver) {
                            val inferred = expr.attribType()
                            chk.checkAssignable(sym.returnType, inferred, expr.span)
                        }
                    }
                }
            }
            else {
                if (ctx.EXTERNAL() == null) {
                    log.diag(missingFunctionBody(ctx.name.span))
                }
            }

            return null
        }

        // ------------------------------------------------------------------
        // parser rules
        // ------------------------------------------------------------------

        override fun visitParseRuleDef(ctx: ForgeParser.ParseRuleDefContext): Type? {
            if (ctx.ARROW() == null) return null
            val sym = ctx.symbol ?: return null
            val outer = env
            env = Env(AttributeContext(Scope(), parser = sym.parser))
            for (option in ctx.parserRuleOption()) {
                withChildScope {
                    val info = parseInfoOf(option.parseRule())
                    val labels = info.labels.map { (name, type) -> VarSymbol(name, type, option.span) }
                    labels.forEach { env.value.scope.enter(it) }
                    option.attach("labels", labels)
                    val type = if (option.producer != null) {
                        withExpected(sym.returnType) { option.producer.attribType() }
                    } else {
                        info.type ?: run {
                            log.diag(canNotInferParseValue(option.span, info.reason ?: "unknown"))
                            ErrorType
                        }
                    }
                    chk.checkAssignable(sym.returnType, type, option.span)
                    option.setType(type)
                }
            }
            env = outer
            return null
        }

        private fun join(types: List<Type>): Type? {
            if (types.any { it == ErrorType }) return ErrorType
            var res = types.firstOrNull() ?: return null
            for (t in types.drop(1)) res = this@Attr.types.sharedSuperType(res, t) ?: return null
            return res
        }

        private fun optional(type: Type): Type = if (type == ErrorType || type == NullType) type else OptionalType(type)

        private fun list(type: Type): Type = if (type == ErrorType) type else ListType(type)

        private fun parseInfoOf(ctx: ForgeParser.ParseRuleContext): ParseInfo =
            ctx.setParseInfo(parseInfoOf(ctx.parserAlternative()))

        private fun parseInfoOf(ctx: ForgeParser.ParserAlternativeContext): ParseInfo {
            val alts = ctx.parserSequence().map { parseInfoOf(it) }
            if (alts.size == 1) return ctx.setParseInfo(alts[0])
            val labels = LinkedHashMap<String, Type>()
            for (name in alts.flatMap { it.labels.keys }.distinct()) {
                val present = alts.mapNotNull { it.labels[name] }
                var type = join(present) ?: run {
                    log.diag(typeMismatch(ctx.span, present, present))
                    ErrorType
                }
                if (present.size < alts.size) type = optional(type)
                labels[name] = type
            }
            val kind = if (alts.all { it.kind == ParseInfo.Kind.TEXT }) ParseInfo.Kind.TEXT else ParseInfo.Kind.VALUE
            val missing = alts.firstOrNull { it.type == null }
            val info = if (missing != null) {
                ParseInfo(kind, null, labels, missing.reason)
            } else {
                val type = join(alts.map { it.type!! })
                ParseInfo(kind, type, labels, if (type == null) "the alternatives produce the incompatible types ${alts.joinToString(", ") { it.type.toString() }}" else null)
            }
            return ctx.setParseInfo(info)
        }

        private fun parseInfoOf(ctx: ForgeParser.ParserSequenceContext): ParseInfo {
            val elems = ctx.parserLabeled().map { it to parseInfoOf(it) }
            val labels = LinkedHashMap<String, Type>()
            for ((elemCtx, elem) in elems) {
                for ((name, type) in elem.labels) {
                    if (name in labels) log.diag(labelAlreadyDefined(elemCtx.span, name))
                    labels[name] = type
                }
            }
            if (elems.isEmpty()) return ctx.setParseInfo(ParseInfo(ParseInfo.Kind.VALUE, NullType, labels))
            if (elems.size == 1) return ctx.setParseInfo(ParseInfo(elems[0].second.kind, elems[0].second.type, labels, elems[0].second.reason))
            val values = elems.filter { it.second.kind == ParseInfo.Kind.VALUE }
            val info = when (values.size) {
                0 -> ParseInfo(ParseInfo.Kind.TEXT, StrType, labels)
                1 -> ParseInfo(ParseInfo.Kind.VALUE, values[0].second.type, labels, values[0].second.reason)
                else -> flattened(values.map { it.second.type }, labels) ?: ParseInfo(
                    ParseInfo.Kind.VALUE, null, labels,
                    "'${ctx.text}' contains several elements that produce values (${values.joinToString(", ") { it.first.text }})"
                )
            }
            return ctx.setParseInfo(info)
        }

        /** `x ("," x)*`: values of type T or List<T> concatenate to a List<T> */
        private fun flattened(types: List<Type?>, labels: LinkedHashMap<String, Type>): ParseInfo? {
            if (types.any { it == null || it == ErrorType } || types.none { it is ListType }) return null
            val elem = join(types.map { if (it is ListType) it.elem else it!! }) ?: return null
            return ParseInfo(ParseInfo.Kind.VALUE, ListType(elem), labels, flatten = true)
        }

        private fun parseInfoOf(ctx: ForgeParser.ParserLabeledContext): ParseInfo {
            val inner = parseInfoOf(ctx.parserPostfix())
            if (ctx.label == null) return ctx.setParseInfo(inner)
            val label = ctx.label.text
            val type = inner.type ?: run {
                log.diag(canNotInferParseValue(ctx.label.span, inner.reason ?: "unknown"))
                ErrorType
            }
            val labels = LinkedHashMap(inner.labels)
            if (label in labels) log.diag(labelAlreadyDefined(ctx.label.span, label))
            labels[label] = type
            return ctx.setParseInfo(ParseInfo(ParseInfo.Kind.VALUE, type, labels))
        }

        private fun parseInfoOf(ctx: ForgeParser.ParserPostfixContext): ParseInfo {
            val atom = parseInfoOf(ctx.atom)
            val info = when {
                ctx.QUESTIONMARK() != null -> ParseInfo(
                    ParseInfo.Kind.VALUE, atom.type?.let { optional(it) },
                    LinkedHashMap(atom.labels.mapValues { optional(it.value) }), atom.reason,
                )
                ctx.STAR() != null || ctx.PLUS() != null -> ParseInfo(
                    ParseInfo.Kind.VALUE, atom.type?.let { list(it) },
                    LinkedHashMap(atom.labels.mapValues { list(it.value) }), atom.reason,
                )
                else -> atom
            }
            return ctx.setParseInfo(info)
        }

        private fun parseInfoOf(ctx: ForgeParser.ParserAtomContext): ParseInfo {
            val info = when {
                ctx.terminalRule() != null -> {
                    val terminal = ctx.terminalRule()
                    val syntax = terminal.terminalSyntax()
                    if (syntax.fallback != null) {
                        log.diag(regexTakenLiterally(terminal.span, terminal.text, syntax.fallback, syntax.pattern))
                    }
                    if (!syntax.literal && Pattern.compile(syntax.pattern).matcher("").lookingAt()) {
                        log.diag(terminalMatchesEmpty(terminal.span, syntax.pattern))
                    }
                    else if (syntax.pattern.isEmpty()) {
                        log.diag(terminalMatchesEmpty(terminal.span, syntax.pattern))
                    }
                    terminal.setType(StrType)
                    ParseInfo(ParseInfo.Kind.TEXT, StrType, LinkedHashMap())
                }
                ctx.nonterminalRule() != null -> {
                    val nonterminal = ctx.nonterminalRule()
                    val parser = env.value.parser!!
                    val name = nonterminal.name.text
                    val ruleSym = resolve.resolveParserRule(parser, name) as? ParserRuleSymbol
                    if (ruleSym == null) {
                        log.diag(parserRuleNotFound(nonterminal.name.span, parser.name, name))
                        ParseInfo(ParseInfo.Kind.VALUE, ErrorType, LinkedHashMap())
                    } else {
                        nonterminal.setSym(ruleSym)
                        nonterminal.setType(ruleSym.returnType)
                        ParseInfo(ParseInfo.Kind.VALUE, ruleSym.returnType, LinkedHashMap())
                    }
                }
                else -> parseInfoOf(ctx.parseRule())
            }
            return ctx.setParseInfo(info)
        }

        // ------------------------------------------------------------------
        // expressions
        // ------------------------------------------------------------------

        override fun visitIntValue(ctx: ForgeParser.IntValueContext): Type {
            return ctx.setType(IntType)
        }

        override fun visitFloatValue(ctx: ForgeParser.FloatValueContext): Type {
            return ctx.setType(FloatType)
        }

        override fun visitStringValue(ctx: ForgeParser.StringValueContext): Type {
            return ctx.setType(StrType)
        }

        override fun visitBoolValue(ctx: ForgeParser.BoolValueContext): Type {
            return ctx.setType(BoolType)
        }

        override fun visitNullValue(ctx: ForgeParser.NullValueContext): Type {
            return ctx.setType(NullType)
        }

        override fun visitEnclosed(ctx: ForgeParser.EnclosedContext): Type {
            return ctx.setType(ctx.expression().attribType())
        }

        override fun visitCast(ctx: ForgeParser.CastContext): Type {
            val type = chk.checkExists(ctx.ty.toType())
            val valueType = ctx.value.attribType()
            val ok = valueType == ErrorType || type == ErrorType ||
                    types.isAssignable(type, valueType) || types.isAssignable(valueType, type) ||
                    (valueType in listOf(IntType, FloatType) && type in listOf(IntType, FloatType))
            if (!ok) log.diag(typeMismatch(ctx.span, type, valueType))
            return ctx.setType(type)
        }

        override fun visitNullCheck(ctx: ForgeParser.NullCheckContext): Type {
            val type = ctx.value.attribType()
            return ctx.setType(if (type is OptionalType) type.elem else type)
        }

        override fun visitListLiteral(ctx: ForgeParser.ListLiteralContext): Type {
            val expectedElem = (env.value.expected as? ListType)?.elem
            val elemTypes = ctx.expression().map { withExpected(expectedElem) { it.attribType() } }
            val res = chk.checkAllAssignable(elemTypes, expectedElem)
            return ctx.setType(ListType(res))
        }

        override fun visitListComprehension(ctx: ForgeParser.ListComprehensionContext): Type {
            val iterable = withExpected(null) { ctx.iterable.attribType() }
            chk.checkAssignable(ListType(ArbitraryType), iterable, ctx.iterable.span)
            val iterType = if (iterable is ListType) iterable.elem else ErrorType

            if (ctx.acc == null) {
                return withChildScope {
                    val iterSym = VarSymbol(ctx.name.text, iterType, ctx.name.span)
                    ctx.setSym(iterSym)
                    env.value.scope.enter(iterSym)
                    val expectedElem = (env.value.expected as? ListType)?.elem
                    ctx.setType(ListType(withExpected(expectedElem) { ctx.put.attribType() }))
                }
            } else {
                val expected = env.value.expected
                val accInitType = withExpected(expected) { ctx.accInit.attribType() }
                val accType = if (expected != null && types.isAssignable(expected, accInitType)) expected else accInitType
                return withChildScope {
                    val iterSym = VarSymbol(ctx.name.text, iterType, ctx.name.span)
                    val accSym = VarSymbol(ctx.acc.text, accType, ctx.acc.span)
                    ctx.attach("symbols", iterSym to accSym)
                    env.value.scope.enter(iterSym)
                    env.value.scope.enter(accSym)
                    val put = withExpected(accType) { ctx.put.attribType() }
                    chk.checkAssignable(accType, put, ctx.put.span)
                    ctx.setType(accType)
                }
            }
        }

        override fun visitMapLiteral(ctx: ForgeParser.MapLiteralContext): Type {
            val expected = env.value.expected
            var expectedKey: Type? = null
            var expectedValue: Type? = null
            if (expected is MapType) {
                expectedKey = expected.key
                expectedValue = expected.value
            }
            val keyTypes = ctx.expression().filterIndexed { i, _ -> i % 2 == 0 }.map { withExpected(expectedKey) { it.attribType() } }
            val valueTypes = ctx.expression().filterIndexed { i, _ -> i % 2 == 1 }.map { withExpected(expectedValue) { it.attribType() } }
            val keyRes = chk.checkAllAssignable(keyTypes, expectedKey)
            val valueRes = chk.checkAllAssignable(valueTypes, expectedValue)
            return ctx.setType(MapType(keyRes, valueRes))
        }

        override fun visitAddSub(ctx: ForgeParser.AddSubContext): Type {
            val lhsTy = withExpected(null) { ctx.left.attribType() }
            val rhsTy = withExpected(null) { ctx.right.attribType() }
            val isAdd = ctx.PLUS() != null
            return ctx.setType(if (isAdd) {
                chk.checkAdditive(lhsTy, rhsTy)
            } else {
                chk.checkNumericOp(lhsTy, rhsTy, "-")
            })
        }

        override fun visitMulDivMod(ctx: ForgeParser.MulDivModContext): Type {
            val lhsTy = withExpected(null) { ctx.left.attribType() }
            val rhsTy = withExpected(null) { ctx.right.attribType() }
            val isMod = ctx.PERCENT() != null
            return ctx.setType(if (isMod) {
                chk.checkAssignable(IntType, lhsTy, ctx.left.span)
                chk.checkAssignable(IntType, rhsTy, ctx.right.span)
                IntType
            } else {
                chk.checkNumericOp(lhsTy, rhsTy, ctx.STAR()?.let { "*" } ?: "/")
            })
        }

        override fun visitRelational(ctx: ForgeParser.RelationalContext): Type {
            val lhsTy = withExpected(null) { ctx.left.attribType() }
            val rhsTy = withExpected(null) { ctx.right.attribType() }
            if (!(lhsTy == StrType && rhsTy == StrType)) {
                chk.checkNumericOp(lhsTy, rhsTy, ctx.op.text)
            }
            return ctx.setType(BoolType)
        }

        override fun visitInOp(ctx: ForgeParser.InOpContext): Type {
            val elemTy = withExpected(null) { ctx.left.attribType() }
            val containerTy = withExpected(null) { ctx.right.attribType() }
            when (containerTy) {
                is ListType -> chk.checkAssignable(containerTy.elem, elemTy, ctx.left.span)
                is MapType -> chk.checkAssignable(containerTy.key, elemTy, ctx.left.span)
                ErrorType -> {}
                else -> log.diag(typeMismatch(ctx.right.span, listOf(ListType(ArbitraryType), MapType(ArbitraryType, ArbitraryType)), containerTy))
            }
            return ctx.setType(BoolType)
        }

        override fun visitConcat(ctx: ForgeParser.ConcatContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = withExpected(lhsTy) { ctx.right.attribType() }
            return ctx.setType(chk.checkConcatOp(lhsTy, rhsTy))
        }

        override fun visitContainerAccess(ctx: ForgeParser.ContainerAccessContext): Type {
            val lhsTy = withExpected(null) { ctx.container.attribType() }
            val rhsTy = withExpected((lhsTy as? MapType)?.key) { ctx.key.attribType() }
            return ctx.setType(chk.checkContainerAccess(lhsTy, rhsTy))
        }

        override fun visitContextualized(ctx: ForgeParser.ContextualizedContext): Type {
            // `expr where fun f(..): T = ..` defines a local (possibly recursive) helper function for `expr`
            val func = ctx.`fun`
            return withChildScope {
                if (func.receiver != null) {
                    log.diag(invalidAnnotation(func.receiver.span, "local functions can not have a receiver"))
                }
                val sym = FunctionSymbol(
                    null,
                    func.name.text,
                    func.parameter().map { VarSymbol(it.name.text, chk.checkExists(it.ty.toType()), it.name.span, VarSymbol.VarKind.PARAMETER) },
                    chk.checkExists(func.ty.toType()),
                    func.name.span,
                )
                func.setSym(sym)
                ctx.setSym(sym)
                env.value.scope.enter(sym)
                func.accept(this)
                ctx.setType(ctx.expr.attribType())
            }
        }

        override fun visitElvis(ctx: ForgeParser.ElvisContext): Type {
            val exprTy = withExpected(env.value.expected?.let { optional(it) }) { ctx.expr.attribType() }
            val alt = withExpected(env.value.expected ?: (exprTy as? OptionalType)?.elem ?: exprTy) { ctx.alt.attribType() }
            val expr = if (exprTy is OptionalType) exprTy.elem else exprTy
            val shared = chk.checkShareCommonSuperType(expr, alt, env.value.expected)
            return ctx.setType(shared)
        }

        override fun visitNegation(ctx: ForgeParser.NegationContext): Type {
            val exprTy = ctx.expression().attribType()
            return ctx.setType(chk.checkNegation(exprTy))
        }

        override fun visitInversion(ctx: ForgeParser.InversionContext): Type {
            val exprTy = ctx.expression().attribType()
            return ctx.setType(chk.checkInversion(exprTy))
        }

        override fun visitEquality(ctx: ForgeParser.EqualityContext): Type {
            val expLhs = withExpected(null) { ctx.left.attribType() }
            val expRhs = withExpected(expLhs) { ctx.right.attribType() }
            chk.checkShareCommonSuperType(expLhs, expRhs, null)
            return ctx.setType(BoolType)
        }

        private fun attribBoolOrIntOp(left: ForgeParser.ExpressionContext, right: ForgeParser.ExpressionContext): Type {
            val lhsTy = withExpected(null) { left.attribType() }
            val rhsTy = withExpected(null) { right.attribType() }
            chk.checkBoolOrInt(lhsTy)
            if (lhsTy is ErrorType) return chk.checkBoolOrInt(rhsTy)
            chk.checkAssignable(lhsTy, rhsTy, right.span)
            return lhsTy
        }

        override fun visitLogicalAnd(ctx: ForgeParser.LogicalAndContext): Type =
            ctx.setType(attribBoolOrIntOp(ctx.left, ctx.right))

        override fun visitLogicalOr(ctx: ForgeParser.LogicalOrContext): Type =
            ctx.setType(attribBoolOrIntOp(ctx.left, ctx.right))

        override fun visitLogicalXor(ctx: ForgeParser.LogicalXorContext): Type =
            ctx.setType(attribBoolOrIntOp(ctx.left, ctx.right))

        override fun visitIand(ctx: ForgeParser.IandContext): Type {
            val lhsTy = withExpected(null) { ctx.left.attribType() }
            val rhsTy = withExpected(null) { ctx.right.attribType() }
            chk.checkAssignable(IntType, lhsTy, ctx.left.span)
            chk.checkAssignable(IntType, rhsTy, ctx.right.span)
            return ctx.setType(IntType)
        }

        override fun visitIor(ctx: ForgeParser.IorContext): Type {
            val lhsTy = withExpected(null) { ctx.left.attribType() }
            val rhsTy = withExpected(null) { ctx.right.attribType() }
            chk.checkAssignable(IntType, lhsTy, ctx.left.span)
            chk.checkAssignable(IntType, rhsTy, ctx.right.span)
            return ctx.setType(IntType)
        }

        override fun visitIdentifier(ctx: ForgeParser.IdentifierContext): Type =
            attribIdent(ctx, setOf(Kind.VAL))

        override fun visitFieldAccess(ctx: ForgeParser.FieldAccessContext): Type {
            val recv = ctx.receiver
            val recvTy = withExpected(null) { recv.attribOwner(setOf(Kind.VAL, Kind.TYP)) }
            if (recvTy == ErrorType) return ctx.setType(ErrorType)

            val fieldName = ctx.name.text

            val sym = resolveMember(recvTy, fieldName, receiverKind(recv), Kind.VAL, recv.span)

            if (sym is InhSymbol && recv !is ForgeParser.ThisExprContext) {
                log.diag(invalidExternalInhAttribAccess(ctx.span))
            }

            if (sym == null) {
                log.diag(attributeNotFound(ctx.name.span, recvTy.toString(), fieldName))
                return ctx.setType(ErrorType)
            }
            ctx.setSym(sym)
            return ctx.setType(sym.type)
        }

        override fun visitFunctionCall(ctx: ForgeParser.FunctionCallContext): Type {
            val name = ctx.name.text
            val sym = lookup(name) as? CallableSymbol ?: chk.resolveFunction(name, ctx.name.span)
            if (sym == null) {
                ctx.expression().forEach { withExpected(null) { it.attribType() } }
                return ctx.setType(ErrorType)
            }
            ctx.setSym(sym)
            attribArguments(ctx.expression(), sym.params.map { it.type }, ctx.span)
            return ctx.setType(sym.returnType)
        }

        override fun visitMethodCall(ctx: ForgeParser.MethodCallContext): Type {
            val recv = ctx.receiver
            val args = ctx.expression().drop(1)
            val recvTy = withExpected(null) { recv.attribOwner(setOf(Kind.VAL, Kind.TYP)) }

            if (recvTy == ErrorType) {
                args.forEach { withExpected(null) { it.attribType() } }
                return ctx.setType(ErrorType)
            }

            val methodName = ctx.name.text
            val sym = resolveMember(recvTy, methodName, receiverKind(recv), Kind.MTH, recv.span)

            if (sym == null) {
                log.diag(methodNotFound(ctx.name.span, recvTy.toString(), methodName))
                args.forEach { withExpected(null) { it.attribType() } }
                return ctx.setType(ErrorType)
            }
            ctx.setSym(sym)

            val symType = sym.type
            if (symType !is CallableType) {
                log.diag(typeMismatch(ctx.name.span, FunctionType(args.map { ArbitraryType }, ArbitraryType), symType))
                return ctx.setType(ErrorType)
            }

            attribArguments(args, symType.parameters, ctx.span)
            return ctx.setType(symType.returnType)
        }

        override fun visitIfElse(ctx: ForgeParser.IfElseContext): Type {
            val cond = withExpected(BoolType) { ctx.cond.attribType() }
            chk.checkAssignable(BoolType, cond, ctx.cond.span)
            val then = ctx.then.attribType()
            val alt = ctx.alt.attribType()
            return ctx.setType(chk.checkShareCommonSuperType(then, alt, env.value.expected))
        }

        override fun visitThisExpr(ctx: ForgeParser.ThisExprContext): Type {
            val receiver = env.value.receiver
            if (receiver == null) log.diag(canNotFindIdentifier(ctx.span, "this"))
            return ctx.setType(receiver ?: ErrorType)
        }

        override fun visitMatch(ctx: ForgeParser.MatchContext): Type {
            val matched = withExpected(null) { ctx.switch_.attribType() }
            val matchCaseReturnTypes = mutableListOf<Type>()
            if (ctx.matchCase().isEmpty() && ctx.alt == null) {
                log.diag(noMatchCaseSpecified(ctx.MATCH().span))
            }
            withMatched(matched) {
                matchCaseReturnTypes.addAll(ctx.matchCase().map {
                    it.attribType()
                })
                if (ctx.alt != null) {
                    matchCaseReturnTypes.add(ctx.alt.attribType())
                }
            }
            return ctx.setType(chk.checkAllAssignable(matchCaseReturnTypes, env.value.expected))
        }

        override fun visitMatchCase(ctx: ForgeParser.MatchCaseContext): Type {
            return withChildScope {
                val matched = env.value.matched!!
                val patternTy = withExpected(matched) { ctx.pattern().attribType() }
                chk.checkAssignable(
                    matched,
                    patternTy,
                    ctx.pattern().span
                )
                if (ctx.cond != null) {
                    val cond = withExpected(BoolType) { ctx.cond.attribType() }
                    chk.checkAssignable(BoolType, cond, ctx.cond.span)
                }
                val valueTy = ctx.value.attribType()
                ctx.setType(valueTy)
            }
        }

        override fun visitPattern(ctx: ForgeParser.PatternContext): Type {
            val expected = env.value.expected!!
            return ctx.setType(if (ctx.exprPattern() != null) {
                val exprTy = ctx.exprPattern().expression().attribType()
                ctx.exprPattern().setType(exprTy)
                chk.checkShareCommonSuperType(expected, exprTy, null)
                expected
            } else if (ctx.typePattern() != null) {
                chk.checkAssignable(expected, ctx.typePattern().attribType(), ctx.span)
            } else if (ctx.splitPattern() != null) {
                chk.checkAssignable(expected, ctx.splitPattern().attribType(), ctx.span)
            } else if (ctx.listPattern() != null) {
                chk.checkAssignable(expected, ctx.listPattern().attribType(), ctx.span)
            } else {
                throw AssertionError("Should not reach here")
            })
        }

        override fun visitExpressionArg(ctx: ForgeParser.ExpressionArgContext): Type {
            val expected = env.value.expected!!
            val exprTy = withExpected(expected) { ctx.exprPattern().expression().attribType() }
            ctx.exprPattern().setType(exprTy)
            chk.checkShareCommonSuperType(expected, exprTy, null)
            return ctx.setType(expected)
        }

        override fun visitTypePatternArg(ctx: ForgeParser.TypePatternArgContext): Type =
            ctx.setType(ctx.typePattern().attribType())

        override fun visitWildcardPatternArg(ctx: ForgeParser.WildcardPatternArgContext): Type =
            ctx.setType(ctx.wildcardPattern().attribType())

        override fun visitPatternVariableArg(ctx: ForgeParser.PatternVariableArgContext): Type =
            ctx.setType(ctx.patternVariable().attribType())

        override fun visitListPatternArg(ctx: ForgeParser.ListPatternArgContext): Type =
            ctx.setType(ctx.listPattern().attribType())

        override fun visitSplitHead(ctx: ForgeParser.SplitHeadContext): Type {
            val expected = env.value.expected!!
            val exprPattern = ctx.exprPattern()
            if (exprPattern != null) {
                val exprTy = withExpected(expected) { exprPattern.expression().attribType() }
                exprPattern.setType(exprTy)
                chk.checkShareCommonSuperType(expected, exprTy, null)
                return ctx.setType(expected)
            }
            return ctx.setType(ctx.getChild(0).let { (it as ParserRuleContext).attribType() })
        }

        override fun visitWildcardPattern(ctx: ForgeParser.WildcardPatternContext): Type {
            return ctx.setType(env.value.expected!!)
        }

        override fun visitPatternVariable(ctx: ForgeParser.PatternVariableContext): Type {
            val type = env.value.expected!!
            val sym = VarSymbol(ctx.name.text, type, ctx.name.span)
            env.value.scope.enter(sym)
            ctx.setSym(sym)
            return ctx.setType(type)
        }

        override fun visitTypePattern(ctx: ForgeParser.TypePatternContext): Type {
            val recvName = ctx.receiver.text
            val paramPatterns = ctx.patternArg()
            val name = ctx.name.text

            val recv = symTab.enums.values.find { it.name == recvName }
            if (recv == null) {
                log.diag(canNotFindIdentifier(ctx.receiver.span, recvName))
                return ctx.setType(ErrorType)
            }
            val receiver = recv.type

            val sym = resolveMember(receiver, name, Kind.TYP, if (ctx.LPAREN() == null) Kind.VAL else Kind.MTH)
            if (sym !is EnumItemSymbol) {
                log.diag(attributeNotFound(ctx.name.span, receiver.toString(), name))
                paramPatterns.forEach { withExpected(ErrorType) { it.attribType() } }
                return ctx.setType(ErrorType)
            }
            val paramTypes = sym.parameters.map { it.second }
            val argSpan = if (ctx.LPAREN() == null) ctx.name.span else ctx.LPAREN().span + ctx.RPAREN().span
            if (paramPatterns.size < paramTypes.size) {
                log.diag(tooFewArguments(argSpan))
            }
            if (paramPatterns.size > paramTypes.size) {
                log.diag(tooManyArguments(argSpan))
            }
            for ((i, pattern) in paramPatterns.withIndex()) {
                val expected = paramTypes.getOrNull(i) ?: ErrorType
                val got = withExpected(expected) { pattern.attribType() }
                chk.checkAssignable(expected, got, pattern.span)
            }
            ctx.setSym(sym)
            return ctx.setType(receiver)
        }

        override fun visitListPattern(ctx: ForgeParser.ListPatternContext): Type {
            val expected = env.value.expected!!
            if (expected == ErrorType) {
                ctx.patternArg().forEach { withExpected(ErrorType) { it.attribType() } }
                return ctx.setType(ErrorType)
            }
            if (expected !is ListType) {
                log.diag(typeMismatch(ctx.span, ListType(ArbitraryType), expected))
                ctx.patternArg().forEach { withExpected(ErrorType) { it.attribType() } }
                return ctx.setType(ErrorType)
            }
            for (arg in ctx.patternArg()) {
                val ty = withExpected(expected.elem) { arg.attribType() }
                chk.checkAssignable(expected.elem, ty, arg.span)
            }
            return ctx.setType(expected)
        }

        override fun visitSplitPattern(ctx: ForgeParser.SplitPatternContext): Type {
            handleSplitPattern(ctx.element, ctx.rest)
            return ctx.setType(env.value.expected!!)
        }

        override fun visitSplitPatternArg(ctx: ForgeParser.SplitPatternArgContext): Type {
            handleSplitPattern(ctx.element, ctx.rest)
            return ctx.setType(env.value.expected!!)
        }

        private fun handleSplitPattern(
            elem: ParserRuleContext,
            rest: ForgeParser.PatternArgContext
        ) {
            if (chk.checkSplit(env.value.expected!!) == ErrorType) {
                withExpected(ErrorType) {
                    elem.attribType()
                }
                withExpected(ErrorType) {
                    rest.attribType()
                }
            }
            else {
                val expected = (env.value.expected!! as ListType).elem
                withExpected(expected) {
                    chk.checkAssignable(expected, elem.attribType(), elem.span)
                }
                withExpected(ListType(expected)) {
                    chk.checkAssignable(ListType(expected), rest.attribType(), rest.span)
                }
            }
        }

        override fun visitErrorNode(node: ErrorNode): Type {
            log.diag(parseError(node.span, node.text))
            return ErrorType
        }

    }

}
