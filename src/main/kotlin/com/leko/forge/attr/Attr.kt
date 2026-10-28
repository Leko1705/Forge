package com.leko.forge.attr

import ForgeParser
import ForgeParserBaseVisitor
import com.leko.forge.analysis.*
import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.*
import com.leko.forge.util.Diagnostic
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ErrorNode

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
        var delegateIndexVarEnabled: Boolean = false,
    )


    val chk = Check.instance(context)

    val resolve = Resolve.instance(context)

    val symTab = SymTab.instance(context)

    val log = Log.instance(context)


    fun attr(root: ForgeParser.RootContext) {
        root.accept(TypeAttrib())
    }

    private inner class TypeAttrib : ForgeParserBaseVisitor<Type>() {

        private var env: Env<AttributeContext> = Env(AttributeContext(Scope()))

        private fun ParserRuleContext.attribType(): Type {
            return accept(this@TypeAttrib)!!
        }

        private fun attribIdent(ctx: ForgeParser.IdentifierContext, accepted: Set<Kind>): Type {
            val name = ctx.IDENT().text

            var sym = env.value.scope.entries[name]
            if (sym == null) sym = resolve.resolve(name)
            if (sym == null && env.value.delegateIndexVarEnabled) {
                sym = VarSymbol(name, IntType, ctx.IDENT().span)
                env.value.scope.enter(sym)
                env.value.delegateIndexVarEnabled = false
            }

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

        private inline fun <T> withExpected(expected: Type, block: () -> T): T {
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

        private inline fun withDelegateIndexVarEnabled(block: () -> Unit) {
            this.env.value.delegateIndexVarEnabled = true
            block()
            this.env.value.delegateIndexVarEnabled = false
        }

        private inline fun withChildScope(block: () -> Unit) {
            val env = this.env
            this.env = env.child(env.value.copy(scope = Scope()))
            block()
            this.env = env
        }

        private fun resolveMember(owner: Type, member: String, receiverKind: Kind, memberKind: Kind): Symbol? {
            val owner = if (owner is OptionalType) {
                chk.checkAssignable(owner.elem, owner)  // trigger type error
                owner.elem
            }
            else {
                owner
            }
            return resolve.resolve(owner, member, receiverKind, memberKind)
        }

        override fun visitSynDef(ctx: ForgeParser.SynDefContext): Type? {
            val attrSym = ctx.symbol ?: return null

            if (attrSym.owner is ConcreteNodeSymbol) {
                withExpected(attrSym.type) {
                    withReceiver(attrSym.owner.type) {
                        val synTy = ctx.syn.attribType()
                        chk.checkAssignable(attrSym.type, synTy)
                    }
                }
            }
            return null
        }

        override fun visitInhDef(ctx: ForgeParser.InhDefContext): Type {
            return chk.checkAssignable(ctx.symbol!!.type, ctx.init.attribType())
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
                    chk.checkAssignable(attrSym.type, synTy)
                }
            }

            return null
        }

        override fun visitContrib(ctx: ForgeParser.ContribContext): Type? {
            val nodeName = ctx.node.text
            val attrName = ctx.collName.text
            val nodeSym = symTab.concreteNodes.values.find { it.name == nodeName } ?: return null
            val alias = ctx.alias?.text ?: attrName

            withReceiver(nodeSym.type) {
                val recvType = ctx.receiver.attribType()
                var collSym = resolveMember(recvType, attrName, Kind.VAL, Kind.VAL)

                if (collSym == null) {
                    log.diag(collectionAttributeNotFound(ctx.collName.span, nodeName, attrName))
                }
                else if (collSym !is CollSymbol) {
                    log.diag(collectionAttributeExpected(ctx.collName.span))
                    collSym = null
                }


                if (collSym != null) {
                    withExpected(collSym.type) {
                        withChildScope {
                            env.value.scope.enter(VarSymbol(alias, collSym.type, ctx.alias?.span))
                            val valueType = ctx.value.attribType()
                            chk.checkAssignable(collSym.type, valueType)
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
            if (childSym == null) {
                log.diag(canNotFindIdentifier(ctx.child.span, childName))
                return null
            }

            if (key == null) {
                if (childSym.type is ListType) {
                    log.diag(invalidToListDelegation(ctx.span, childName))
                }
                chk.checkAssignable(env.value.expected!!, value.attribType())
            }
            else {
                withChildScope {
                    if (childSym.type !is ListType) {
                        log.diag(invalidListAccessDelegation(ctx.span, childName))
                    }
                    withDelegateIndexVarEnabled {
                        chk.checkAssignable(IntType, key.attribType())
                    }
                    chk.checkAssignable(env.value.expected!!, value.attribType())
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
                    if (ctx.receiver == null) {
                        withExpected(sym.returnType) {
                            val inferred = expr.attribType()
                            chk.checkAssignable(env.value.expected!!, inferred)
                        }

                    } else {
                        withExpected(sym.returnType) {
                            withReceiver(sym.receiver!!.type) {
                                val inferred = expr.attribType()
                                chk.checkAssignable(env.value.expected!!, inferred)
                            }
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

        override fun visitParseRuleDef(ctx: ForgeParser.ParseRuleDefContext): Type? {
            if (ctx.ARROW() == null) return null
            val sym = ctx.symbol ?: return null
            withReceiver(sym.parser.type) {
                val optionTypes = ctx.parserRuleOption().map { it.accept(this) }
                if (optionTypes.all { it != null }) {
                    val sharedType = chk.checkAllAssignable(optionTypes, sym.type)
                    chk.checkAssignable(sym.returnType, sharedType)
                }
                else {
                    log.diag(canNotInferParseValue(ctx.ruleName.span))
                }
            }
            return ctx.setType(sym.type)
        }

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

        override fun visitCast(ctx: ForgeParser.CastContext): Type {
            val type = chk.checkExists(ctx.ty.toType())
            withExpected(type) {
                chk.checkAssignable(type, ctx.value.attribType())
            }
            return ctx.setType(type)
        }

        override fun visitNullCheck(ctx: ForgeParser.NullCheckContext): Type {
            val type = ctx.value.attribType()
            return if (type is OptionalType) type.elem else type
        }

        override fun visitListLiteral(ctx: ForgeParser.ListLiteralContext): Type {
            val elemTypes = ctx.expression().map { it.attribType() }
            val res = chk.checkAllAssignable(elemTypes, (env.value.expected as? ListType)?.elem)
            return ctx.setType(ListType(res))
        }

        override fun visitListComprehension(ctx: ForgeParser.ListComprehensionContext): Type {
            val iterable = ctx.iterable.attribType()
            chk.checkAssignable(ListType(ArbitraryType), iterable)
            val iterType = if (iterable is ListType) iterable.elem else ErrorType

            if (ctx.acc == null) {
                withChildScope {
                    val iterSym = VarSymbol(ctx.name.text, iterType, ctx.name.span)
                    ctx.setSym(iterSym)
                    env.value.scope.enter(iterSym)
                    return ctx.setType(ListType(ctx.put.attribType()))
                }
            } else {
                val accInitType = ctx.accInit.attribType()
                var put: Type = ErrorType
                withChildScope {
                    val iterSym = VarSymbol(ctx.name.text, iterType, ctx.name.span)
                    val accSym = VarSymbol(ctx.acc.text, accInitType, ctx.acc.span)
                    ctx.attach("symbols", iterSym to accSym)
                    env.value.scope.enter(iterSym)
                    env.value.scope.enter(accSym)
                    put = ctx.put.attribType()
                }
                val superType = chk.checkShareCommonSuperType(accInitType, put, env.value.expected)
                return ctx.setType(superType)
            }

            throw AssertionError("Should not reach here")
        }

        override fun visitMapLiteral(ctx: ForgeParser.MapLiteralContext): Type {
            val keyTypes = ctx.expression().filterIndexed { i, _ -> i % 2 == 0 }.map { it.attribType() }
            val valueTypes = ctx.expression().filterIndexed { i, _ -> i % 2 == 1 }.map { it.attribType() }
            val expected = env.value.expected
            var expectedKey: Type? = null
            var expectedValue: Type? = null
            if (expected is MapType) {
                expectedKey = expected.key
                expectedValue = expected.value
            }
            val keyRes = chk.checkAllAssignable(keyTypes, expectedKey)
            val valueRes = chk.checkAllAssignable(valueTypes, expectedValue)
            return ctx.setType(MapType(keyRes, valueRes))
        }

        override fun visitAddSub(ctx: ForgeParser.AddSubContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            val isAdd = ctx.PLUS() != null
            return ctx.setType(if (isAdd) {
                chk.checkAdditive(lhsTy, rhsTy)
            } else {
                chk.checkNumericOp(lhsTy, rhsTy, "-")
            })
        }

        override fun visitMulDivMod(ctx: ForgeParser.MulDivModContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            val isMod = ctx.PERCENT() != null
            return ctx.setType(if (isMod) {
                chk.checkAssignable(IntType, lhsTy)
                chk.checkAssignable(IntType, rhsTy)
                IntType
            } else {
                chk.checkNumericOp(lhsTy, lhsTy, ctx.STAR()?.let { "*" } ?: "/")
            })
        }

        override fun visitConcat(ctx: ForgeParser.ConcatContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = withExpected(lhsTy) { ctx.right.attribType() }
            return ctx.setType(chk.checkConcatOp(lhsTy, rhsTy))
        }

        override fun visitContainerAccess(ctx: ForgeParser.ContainerAccessContext): Type {
            val lhsTy = ctx.container.attribType()
            val prevEnv = env
            if (lhsTy is MapType) env = env.copy(value = env.value.copy(expected = lhsTy.key))
            val rhsTy = ctx.key.attribType()
            this.env = prevEnv
            return ctx.setType(chk.checkContainerAccess(lhsTy, rhsTy))
        }

        override fun visitContextualized(ctx: ForgeParser.ContextualizedContext): Type {
            throw UnsupportedOperationException("should be transformed away")
        }

        override fun visitElvis(ctx: ForgeParser.ElvisContext): Type {
            var expr = ctx.expr.attribType()
            val alt = ctx.alt.attribType()
            if (expr is OptionalType) expr = expr.elem
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
            val expLhs = ctx.left.attribType()
            val expRhs = ctx.right.attribType()
            chk.checkShareCommonSuperType(expLhs, expRhs, null)
            return ctx.setType(BoolType)
        }

        override fun visitLogicalAnd(ctx: ForgeParser.LogicalAndContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            chk.checkBoolOrInt(lhsTy)
            if (lhsTy is ErrorType) return ctx.setType(chk.checkBoolOrInt(rhsTy))
            chk.checkAssignable(lhsTy, rhsTy)
            return ctx.setType(lhsTy)
        }

        override fun visitLogicalOr(ctx: ForgeParser.LogicalOrContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            chk.checkBoolOrInt(lhsTy)
            if (lhsTy is ErrorType) return ctx.setType(chk.checkBoolOrInt(rhsTy))
            chk.checkAssignable(lhsTy, rhsTy)
            return ctx.setType(lhsTy)
        }

        override fun visitLogicalXor(ctx: ForgeParser.LogicalXorContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            chk.checkBoolOrInt(lhsTy)
            if (lhsTy is ErrorType) return ctx.setType(chk.checkBoolOrInt(rhsTy))
            chk.checkAssignable(lhsTy, rhsTy)
            return ctx.setType(lhsTy)
        }

        override fun visitIand(ctx: ForgeParser.IandContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            chk.checkAssignable(IntType, lhsTy)
            chk.checkAssignable(IntType, rhsTy)
           return ctx.setType(IntType)
        }

        override fun visitIor(ctx: ForgeParser.IorContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            chk.checkAssignable(IntType, lhsTy)
            chk.checkAssignable(IntType, rhsTy)
            return ctx.setType(IntType)
        }

        override fun visitIdentifier(ctx: ForgeParser.IdentifierContext): Type =
            attribIdent(ctx, setOf(Kind.VAL))

        override fun visitFieldAccess(ctx: ForgeParser.FieldAccessContext): Type {
            val recv = ctx.receiver
            val recvTy = recv.attribOwner(setOf(Kind.VAL, Kind.TYP))
            if (recvTy == ErrorType) return ctx.setType(ErrorType)

            val recvSym = recv.symbol
            val fieldName = ctx.name.text

            val sym = resolveMember(recvTy, fieldName, recvSym?.kind ?: Kind.VAL, Kind.VAL)

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
            val sym = chk.resolveFunction(ctx.name.text) ?: return ErrorType
            ctx.setSym(sym)
            val paramTypes = sym.params.map { it.type }
            val argTypes = ctx.expression().map { it.attribType() }
            if (argTypes.size < paramTypes.size) {
                log.diag(tooFewArguments(null))
            }
            if (argTypes.size > paramTypes.size) {
                log.diag(tooManyArguments(null))
            }
            for ((got, expected) in argTypes.zip(paramTypes)) {
                withExpected(expected) {
                    chk.checkAssignable(expected, got)
                }
            }
            return ctx.setType(sym.returnType)
        }

        override fun visitMethodCall(ctx: ForgeParser.MethodCallContext): Type {
            val recv = ctx.receiver
            val recvTy = recv.attribOwner(setOf(Kind.VAL, Kind.TYP))
            val argTypes = ctx.expression().drop(1).map { it.attribType() }   // whatever the grammar actually names these

            if (recvTy == ErrorType) return ctx.setType(ErrorType)

            val recvSym = recv.symbol
            val methodName = ctx.name.text
            val sym = resolveMember(recvTy, methodName, if (recvSym?.kind != Kind.MTH) recvSym!!.kind else Kind.VAL, Kind.MTH)

            if (sym == null) {
                log.diag(methodNotFound(ctx.name.span, recvTy.toString(), methodName))
                return ctx.setType(ErrorType)
            }
            ctx.setSym(sym)

            val symType = sym.type
            if (symType !is CallableType) {
                log.diag(typeMismatch(ctx.name.span, FunctionType(argTypes, ArbitraryType), symType))
                return ctx.setType(ErrorType)
            }

            if (argTypes.size < symType.parameters.size) {
                log.diag(tooFewArguments(null))
            }
            if (argTypes.size > symType.parameters.size) {
                log.diag(tooManyArguments(null))
            }
            for ((got, expected) in argTypes.zip(symType.parameters)) {
                withExpected(expected) {
                    chk.checkAssignable(expected, got)
                }
            }
            return ctx.setType(symType.returnType)
        }

        override fun visitIfElse(ctx: ForgeParser.IfElseContext): Type {
            val cond = ctx.cond.attribType()
            chk.checkAssignable(BoolType, cond)
            val then = ctx.then.attribType()
            val alt = ctx.alt.attribType()
            return ctx.setType(chk.checkShareCommonSuperType(then, alt, env.value.expected))
        }

        override fun visitThisExpr(ctx: ForgeParser.ThisExprContext): Type {
            return ctx.setType(env.value.receiver ?: ErrorType)
        }

        override fun visitMatch(ctx: ForgeParser.MatchContext): Type {
            val matched = ctx.switch_.attribType()
            val matchCaseReturnTypes = mutableListOf<Type>()
            if (ctx.matchCase().isEmpty()) {
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
            withChildScope {
                val patternTy = withExpected(env.value.matched!!) { ctx.pattern().attribType() }
                chk.checkAssignable(
                    env.value.matched!!,
                    patternTy
                )
                if (ctx.cond != null) {
                    val cond = ctx.cond.attribType()
                    chk.checkAssignable(BoolType, cond)
                }
                val valueTy = ctx.value.attribType()
                return ctx.setType(valueTy)
            }
            throw AssertionError("Should not reach here")
        }

        override fun visitPattern(ctx: ForgeParser.PatternContext): Type {
            val expected = env.value.expected!!
            return ctx.setType(if (ctx.exprPattern() != null) {
                chk.checkAssignable(expected, ctx.exprPattern().attribType())
            } else if (ctx.typePattern() != null) {
                chk.checkAssignable(expected, ctx.typePattern().attribType())
            } else if (ctx.splitPattern() != null) {
                chk.checkAssignable(expected, ctx.splitPattern().attribType())
            } else if (ctx.listPattern() != null) {
                chk.checkAssignable(expected, ctx.listPattern().attribType())
            } else {
                throw AssertionError("Should not reach here")
            })
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
                log.diag(canNotFindIdentifier(ctx.name.span, recvName))
                return ErrorType
            }
            val receiver = recv.type


            val sym = resolveMember(receiver, name, Kind.TYP, if (paramPatterns.isEmpty()) Kind.VAL else Kind.MTH)
            if (sym !is EnumItemSymbol) {
                log.diag(attributeNotFound(ctx.name.span, receiver.toString(), name))
                return ctx.setType(ErrorType)
            }
            val paramTypes = sym.parameters.map { it.second }
            val argTypes = paramPatterns.zip(paramTypes).map { withExpected(it.second) { it.first.attribType() } }
            val argSpan = if (ctx.LPAREN() == null) ctx.name.span else ctx.LPAREN().span + ctx.RPAREN().span
            if (argTypes.size < paramTypes.size) {
                log.diag(tooFewArguments(argSpan))
            }
            if (argTypes.size > paramTypes.size) {
                log.diag(tooManyArguments(argSpan))
            }
            var i = 0
            for ((got, expected) in argTypes.zip(paramTypes)) {
                if (paramPatterns[i++] is ForgeParser.WildcardPatternArgContext) continue
                withExpected(expected) {
                    chk.checkAssignable(expected, got)
                }
            }
            ctx.setSym(sym)
            return ctx.setType(receiver)
        }

        override fun visitListPattern(ctx: ForgeParser.ListPatternContext): Type {
            val expected = (env.value.expected!! as? ListType)?.elem ?: ArbitraryType
            val patternTypes = ctx.patternArg().map {
                withExpected(expected) {
                    val ty = it.attribType()
                    if (expected != ArbitraryType) {
                        chk.checkAssignable(expected, ty)
                    }
                    ty
                }
            }.filter { it !is ArbitraryType }

            val res = if (patternTypes.isNotEmpty()) {
                chk.checkAllAssignable(patternTypes, (env.value.expected as? ListType)?.elem)
            } else {
                for (pattern in ctx.patternArg()) {
                    log.diag(canNotInferType(pattern.span))
                }
                ArbitraryType
            }
            return ctx.setType(ListType(res))
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
            elem: ForgeParser.PatternArgContext,
            rest: ForgeParser.PatternArgContext
        ) {
            if (chk.checkSplit(env.value.expected!!) == ErrorType) {
                withExpected(ErrorType) {
                    elem.attribType()
                }
                withExpected(ListType(ErrorType)) {
                    rest.attribType()
                }
            }
            else {
                val expected = (env.value.expected!! as ListType).elem
                withExpected(expected) {
                    chk.checkAssignable(expected, elem.attribType())
                }
                withExpected(ListType(expected)) {
                    chk.checkAssignable(ListType(expected), rest.attribType())
                }
            }
        }

        override fun visitParserRuleOption(ctx: ForgeParser.ParserRuleOptionContext): Type? {
            withChildScope {
                val ty = ctx.parseRule().accept(this)
                return if (ctx.producer != null) {
                    ctx.setType(ctx.producer.attribType())
                } else {
                    ctx.setNullableType(ty)
                }
            }
            throw AssertionError("Should not reach here")
        }

        override fun visitParseRule(ctx: ForgeParser.ParseRuleContext): Type? {
            return ctx.setNullableType(ctx.parserAlternative().accept(this))
        }

        override fun visitParserAlternative(ctx: ForgeParser.ParserAlternativeContext): Type? {
            val types = ctx.parserSequence().map { it.accept(this) }.map { if (it is TerminalRuleProduction) it.actual else it }
            if (types.any { it is ErrorType }) return ErrorType
            return if (types.all { it == types.first() }) types.first() else null
        }

        override fun visitParserSequence(ctx: ForgeParser.ParserSequenceContext): Type? {
            val types = ctx.parserLabeled().map { it.accept(this) }
            if (types.any { it is ErrorType }) return ErrorType
            if (types.all { it is TerminalRuleProduction }) {
                return if (types.size == 1) ctx.setType((types.first() as TerminalRuleProduction).actual)
                else ctx.setType(ListType((types.first() as TerminalRuleProduction).actual))
            }
            val nonTerminals = types.filter { it !is TerminalRuleProduction }
            val res = if (nonTerminals.all { it == nonTerminals.first() }) nonTerminals.first() else null
            return ctx.setNullableType(res)
        }

        override fun visitParserPostfix(ctx: ForgeParser.ParserPostfixContext): Type? {
            val atomType = ctx.atom.accept(this)
            if (atomType is ErrorType) return ErrorType
            return when {
                ctx.QUESTIONMARK() != null -> OptionalType(atomType)
                ctx.PLUS() != null || ctx.STAR() != null -> ListType(atomType)
                else -> atomType
            }
        }

        override fun visitParserAtom(ctx: ForgeParser.ParserAtomContext): Type? {
            if (ctx.terminalRule() != null) return ctx.terminalRule().accept(this)
            if (ctx.nonterminalRule() != null) return ctx.nonterminalRule().accept(this)
            return ctx.parseRule().accept(this)
        }

        override fun visitTerminalRule(ctx: ForgeParser.TerminalRuleContext): Type {
            ctx.setType(StrType)
            return TerminalRuleProduction(StrType)
        }

        override fun visitNonterminalRule(ctx: ForgeParser.NonterminalRuleContext): Type {
            val name = ctx.name.text
            val parserType = env.value.receiver as ParserType
            val ruleSym = resolveMember(parserType, name, Kind.TYP, Kind.MTH) as? ParserRuleSymbol
            if (ruleSym == null) {
                log.diag(parserRuleNotFound(ctx.name.span, parserType.name, name))
                return ctx.setType(ErrorType)
            }
            ctx.setSym(ruleSym)
            return ctx.setType(ruleSym.returnType)
        }

        override fun visitParserLabeled(ctx: ForgeParser.ParserLabeledContext): Type? {
            var type = ctx.parserPostfix().accept(this)
            if (ctx.label == null) return type
            val label = ctx.label.text
            if (type is TerminalRuleProduction) type = type.actual
            if (type != null) {
                env.value.scope.enter(VarSymbol(label, type, ctx.label.span))
            }
            else {
                log.diag(ruleLabelingNotAllowedHere(ctx.label.span))
                env.value.scope.enter(VarSymbol(label, ErrorType, ctx.label.span))
            }
            return null
        }

        override fun visitErrorNode(node: ErrorNode): Type {
            log.diag(parseError(node.span, node.text))
            return ErrorType
        }

    }

}
