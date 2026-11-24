package com.leko.forge.attr

import ForgeParser
import ForgeParserBaseVisitor
import com.leko.forge.analysis.*
import com.leko.forge.context.Context
import com.leko.forge.model.*
import com.leko.forge.types.*
import com.leko.forge.parse.isStd
import com.leko.forge.parse.interpolated
import com.leko.forge.enter.names
import com.leko.forge.util.Diagnostic
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.RuleNode

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
        /** Set inside `flow` bodies: ports and the flow operations (`entry`, `sequence`, `bound`) are visible. */
        val inFlow: Boolean = false,
        /**
         * While an argument of a call of a generic function is checked: its type parameters not yet bound. In what
         * is expected they are unknown (a lambda infers them), other type variables are those of the enclosing function.
         */
        val pending: Set<String> = emptySet(),
        /**
         * Whether [expected] is that of the expression whose receiver is being checked (`xs.toSet()` with `xs` a
         * comprehension: what is expected of the whole helps with `with acc = []`). It is no reason for type arguments.
         */
        val inherited: Boolean = false,
    )


    val chk = Check.instance(context)

    val resolve = Resolve.instance(context)

    val symTab = SymTab.instance(context)

    val log = Log.instance(context)

    val types = Types.instance(context)

    val typeMap = TypeMap.instance(context)

    private enum class FlowRole { SOURCE, TARGET, CHILD }


    /** The definitions of the constants, attributed when they are first used. */
    private var constants: Map<ConstSymbol, ForgeParser.ConstDefContext> = emptyMap()

    fun attr(root: ForgeParser.RootContext) {
        constants = root.constDef().mapNotNull { def -> def.symbol?.let { it to def } }.toMap()
        root.accept(TypeAttrib())
    }

    private inner class TypeAttrib : ForgeParserBaseVisitor<Type>() {

        private var env: Env<AttributeContext> = Env(AttributeContext(Scope()))

        private fun ParserRuleContext.attribType(): Type {
            val span = span
            return log.at(span) { accept(this@TypeAttrib)!! }.also { log.lastAnalyzed = span }
        }

        /** The declarations: their diagnostics without a span of their own are reported at them. */
        override fun visitChildren(node: RuleNode): Type? {
            var result = defaultResult()
            for (i in 0 until node.childCount) {
                if (!shouldVisitNextChild(node, result)) break
                val child = node.getChild(i)
                val childResult = if (child is ParserRuleContext) log.at(child.span) { child.accept(this) } else child.accept(this)
                result = aggregateResult(result, childResult)
            }
            return result
        }

        private fun attribIdent(ctx: ForgeParser.IdentifierContext, accepted: Set<Kind>): Type {
            val name = ctx.IDENT().text

            var sym = lookupLocal(name)
            if (sym == null) {
                val port = symTab.ports[name]
                if (port != null) {
                    if (!env.value.inFlow) {
                        log.diag(portOnlyInFlows(ctx.IDENT().span, name))
                        return ctx.setType(ErrorType)
                    }
                    ctx.attach("port", port)
                    return ctx.setType(PortType)
                }
            }
            if (sym == null) sym = symTab.constants[name]?.also { attribConst(it) }
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

            if (sym is FunctionSymbol && Kind.VAL in accepted) return functionValue(ctx, sym)
            ctx.setSym(sym)
            if (sym.kind !in accepted) {
                log.diag(invalidSymbolKind(ctx.IDENT().span, sym.kind, accepted))
                return ctx.setType(ErrorType)
            }
            if (sym is TraitSymbol) {
                log.diag(canNotUseAsValue(ctx.IDENT().span, sym.name, "it is a trait"))
                return ctx.setType(ErrorType)
            }
            return ctx.setType(sym.type)
        }

        /**
         * The type of a constant: its value is attributed on its own (no locals, no `this`), the first time the
         * constant is used or defined. A constant whose value uses itself is an error.
         */
        private fun attribConst(sym: ConstSymbol) {
            when (sym.state) {
                ConstSymbol.State.DONE -> return
                ConstSymbol.State.IN_PROGRESS -> {
                    log.diag(constantDependsOnItself(sym.span, sym.name))
                    sym.inferred = ErrorType
                    sym.state = ConstSymbol.State.DONE
                    return
                }
                ConstSymbol.State.NEW -> {}
            }
            val def = constants[sym] ?: return
            sym.state = ConstSymbol.State.IN_PROGRESS
            val outer = env
            env = Env(AttributeContext(Scope(), expected = sym.declared))
            try {
                val type = def.value.attribType()
                findThis(def.value)?.let { log.diag(constantUsesThis(it.span, sym.name)) }
                if (sym.state == ConstSymbol.State.IN_PROGRESS) {
                    sym.declared?.let { chk.checkAssignable(it, type) }
                    sym.inferred = type
                }
            } finally {
                env = outer
                sym.state = ConstSymbol.State.DONE
            }
        }

        private fun findThis(tree: org.antlr.v4.runtime.tree.ParseTree): ForgeParser.ThisExprContext? {
            if (tree is ForgeParser.ThisExprContext) return tree
            for (i in 0 until tree.childCount) findThis(tree.getChild(i))?.let { return it }
            return null
        }

        override fun visitConstDef(ctx: ForgeParser.ConstDefContext): Type? {
            ctx.symbol?.let { attribConst(it) }
            return null
        }

        /** Variables of the enclosing scopes, innermost first. */
        private fun lookupLocal(name: String): Symbol? {
            var e: Env<AttributeContext>? = env
            while (e != null) {
                e.value.scope.entries[name]?.let { return it }
                e = e.next
            }
            return null
        }

        private fun ForgeParser.ExpressionContext.attribOwner(accepted: Set<Kind>): Type = when (this) {
            is ForgeParser.IdentifierContext -> attribIdent(this, accepted)
            else -> attribType()
        }

        private inline fun <T> withExpected(expected: Type, block: () -> T): T {
            val env = this.env
            this.env = env.copy(value = env.value.copy(expected = expected, inherited = false))
            val res = block()
            this.env = env
            return res
        }

        private inline fun <T> asReceiver(block: () -> T): T {
            val env = this.env
            this.env = env.copy(value = env.value.copy(inherited = true))
            val res = block()
            this.env = env
            return res
        }

        /** What is expected of the expression being checked, if it is a reason to infer type arguments. */
        private val inferable: Type? get() = env.value.expected.takeIf { !env.value.inherited }

        private inline fun <T> withPending(pending: Set<String>, block: () -> T): T {
            val env = this.env
            this.env = env.copy(value = env.value.copy(pending = pending))
            val res = block()
            this.env = env
            return res
        }

        /** Whether a type is known: it has none of the type parameters still to be inferred ([AttributeContext.pending]). */
        private fun Type.known(): Boolean = typeVars().none { it in env.value.pending }

        private inline fun <T> withoutExpected(block: () -> T): T {
            val env = this.env
            this.env = env.copy(value = env.value.copy(expected = null, inherited = false))
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

        private inline fun withFlow(receiver: Type, block: () -> Unit) {
            val prev = this.env
            this.env = prev.child(prev.value.copy(scope = Scope(), receiver = receiver, inFlow = true))
            block()
            this.env = prev
        }

        private inline fun withChildScope(block: () -> Unit) {
            val env = this.env
            this.env = env.child(env.value.copy(scope = Scope()))
            block()
            this.env = env
        }

        /** Whether a receiver is a value or a type (`Type.INT`); a call, also of a constructor, is a value. */
        private fun ForgeParser.ExpressionContext.receiverKind(): Kind =
            if (this is ForgeParser.FunctionCallContext) Kind.VAL
            else symbol?.kind?.takeIf { it != Kind.MTH } ?: Kind.VAL

        private fun resolveMember(owner: Type, member: String, receiverKind: Kind, memberKind: Kind): Symbol? {
            val owner = if (owner is OptionalType) {
                chk.checkAssignable(owner.elem, owner)  // trigger type error
                owner.elem
            }
            else {
                owner
            }
            if (owner is TypeVar && receiverKind == Kind.VAL) return boundMember(owner, member, memberKind)
            return resolve.resolve(owner, member, receiverKind, memberKind)
        }

        /**
         * A member of a type parameter, from its bounds: of a trait (`fun <T: Ord>`: `x.compareTo(y)`), which the
         * function receives as a hidden parameter, or of a type (`fun <T: Expr>`: `x.type`).
         */
        private fun boundMember(owner: TypeVar, member: String, memberKind: Kind): Symbol? {
            for (bound in types.typeBounds[owner.name].orEmpty()) {
                val trait = resolve.resolve(bound) as? TraitSymbol
                if (trait != null) {
                    val m = trait.members.firstOrNull { it.name == member && (it.isProperty == (memberKind == Kind.VAL)) } ?: continue
                    val self = mapOf(SELF to owner)
                    return FunctionSymbol(
                        trait, m.name,
                        m.params.map { VarSymbol(it.name, it.type.substitute(self), it.span, it.varKind) },
                        m.returnType.substitute(self), m.span,
                        isOperator = m.name in OPERATORS,
                        isProperty = m.isProperty,
                        witnessOf = owner.name,
                    )
                }
                resolve.resolve(bound, member, Kind.VAL, memberKind)?.let { return it }
            }
            return null
        }

        // ---- bounded generics -----------------------------------------------------------------------

        /**
         * Checks that the type arguments of a use of a generic function satisfy its bounds; attaches to [ctx]
         * the members of traits the function receives (its witnesses), in the order of its hidden parameters.
         */
        private fun checkBounds(ctx: ParserRuleContext, use: Symbol) {
            if (use !is FunctionSymbol) return
            if (use.declaration.bounds.isEmpty()) return
            ctx.attach("witnesses", witnessesFor(ctx, use))
        }

        private fun witnessesFor(ctx: ParserRuleContext, use: FunctionSymbol): List<Witness> {
            val decl = use.declaration
            val witnesses = mutableListOf<Witness>()
            for (tv in decl.typeParams) {
                val actual = use.typeArgs[tv] ?: continue
                for (bound in decl.bounds[tv].orEmpty()) {
                    val boundType = bound.substitute(use.typeArgs)
                    val trait = resolve.resolve(bound) as? TraitSymbol
                    if (trait == null) {
                        if (actual != ErrorType && !types.isAssignable(boundType, actual)) {
                            log.diag(boundNotSatisfied(ctx.span, actual, boundType.toString(), "it is not a subtype"))
                        }
                        continue
                    }
                    for (member in trait.members) {
                        witnesses += witness(ctx, actual, trait, member)
                    }
                }
            }
            return witnesses
        }

        /** What a type argument has for a member of a trait: a member of its own, or a witness of a type parameter. */
        private fun witness(ctx: ParserRuleContext, actual: Type, trait: TraitSymbol, member: TraitMember): Witness {
            if (actual == ErrorType || actual == NothingType) return Witness(trait, member, actual, null, null)
            if (actual is TypeVar) {
                val has = types.typeBounds[actual.name].orEmpty().any { (resolve.resolve(it) as? TraitSymbol)?.name == trait.name }
                if (!has) log.diag(boundNotSatisfied(ctx.span, actual, displayName(trait.name), "add the bound: <${actual.name}: ${displayName(trait.name)}>"))
                return Witness(trait, member, actual, null, actual.name)
            }
            val self = mapOf(SELF to actual)
            val found = (if (actual is OptionalType) null else resolve.resolve(actual, member.name, Kind.VAL, if (member.isProperty) Kind.VAL else Kind.MTH)) as? FunctionSymbol
            val ok = found != null && found.params.size == member.params.size &&
                found.params.zip(member.params).all { (p, m) -> types.isAssignable(p.type, m.type.substitute(self)) } &&
                types.isAssignable(member.returnType.substitute(self), found.returnType)
            if (!ok) {
                val signature = (if (member.isProperty) "val " else "fun ") + "$actual.${member.name}" +
                    (if (member.isProperty) "" else member.params.joinToString(", ", "(", ")") { "${it.name}: ${it.type.substitute(self)}" }) +
                    ": " + member.returnType.substitute(self)
                log.diag(boundNotSatisfied(ctx.span, actual, displayName(trait.name), "it needs $signature"))
                return Witness(trait, member, actual, null, null)
            }
            val nested = if (found.declaration.bounds.isNotEmpty()) witnessesFor(ctx, found) else emptyList()
            return Witness(trait, member, actual, found, null, nested)
        }

        override fun visitSynDef(ctx: ForgeParser.SynDefContext): Type? {
            val attrSym = ctx.symbol ?: return null

            if (ctx.syn != null) {
                withExpected(attrSym.type) {
                    withReceiver(attrSym.owner.type) {
                        val synTy = ctx.syn.attribType()
                        chk.checkAssignable(attrSym.type, synTy)
                    }
                }
            }
            return null
        }

        override fun visitCircularDef(ctx: ForgeParser.CircularDefContext): Type? {
            val attrSym = ctx.symbol ?: return null
            withExpected(attrSym.type) {
                withReceiver(attrSym.owner.type) {
                    chk.checkAssignable(attrSym.type, ctx.init.attribType())
                    chk.checkAssignable(attrSym.type, ctx.body.attribType())
                }
            }
            return null
        }

        override fun visitInhDef(ctx: ForgeParser.InhDefContext): Type {
            val sym = ctx.symbol ?: return ErrorType
            return withExpected(sym.type) { chk.checkAssignable(sym.type, ctx.init.attribType()) }
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
            val nodeSym = resolve.resolve(nodeName) as? NodeSymbol
            if (nodeSym == null) {
                log.diag(nodeNotFound(ctx.node.span, nodeName))
                return null
            }
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
                    env.value.scope.members.firstOrNull()?.let { ctx.attach("binder", it) }
                    chk.checkAssignable(env.value.expected!!, value.attribType())
                }
            }

            return null
        }

        override fun visitFunction(ctx: ForgeParser.FunctionContext): Type? {
            val expr = ctx.expression()

            val sym = ctx.symbol ?: return null
            resolve.inStd = ctx.isStd()
            checkBoundDeclarations(ctx)
            currentTypeParams = sym.typeParams
            types.typeBounds = sym.bounds

            if (expr != null) {
                withChildScope {

                    sym.params.forEach { env.value.scope.enter(it) }

                    if (ctx.EXT() != null) {
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
                if (ctx.EXT() == null) {
                    log.diag(missingFunctionBody(ctx.name.span))
                }
            }
            resolve.inStd = false
            currentTypeParams = emptyList()
            types.typeBounds = emptyMap()

            return null
        }

        /** The type parameters of the function being checked; types written in it may use them. */
        private var currentTypeParams: List<String> = emptyList()

        /** A bound is a trait or a type: `fun <T: Ord & Expr>`; an `ext fun` can not have traits as bounds. */
        private fun checkBoundDeclarations(ctx: ForgeParser.FunctionContext) {
            val names = ctx.typeParams().names()
            for (param in ctx.typeParams()?.typeParam().orEmpty()) {
                for (bound in param.bounds) {
                    val type = bound.toType().withTypeParams(names)
                    val sym = (type as? ComplexType)?.let { resolve.resolve(it) }
                    when {
                        sym is TraitSymbol -> if (ctx.EXT() != null) log.diag(invalidBound(bound.span, "an ext fun can not require traits"))
                        type is TypeVar -> log.diag(invalidBound(bound.span, "a bound is a trait or a type, not a type parameter"))
                        else -> chk.checkExists(type, bound)
                    }
                }
            }
        }

        /** The parser whose rule is being checked. */
        private var currentParser: ParserSymbol? = null

        override fun visitParseRuleDef(ctx: ForgeParser.ParseRuleDefContext): Type? {
            val sym = ctx.symbol ?: return null
            val abstract = symTab.parsers.values.firstOrNull { it.name == sym.parser.name }
                ?.let { typeMap.parserRuleDefs[it] }?.firstOrNull { it.name == sym.name }
            if (ctx.ARROW() == null) {
                // an abstract rule: needs alternatives
                val hasAlternatives = typeMap.parserRuleImpls[sym.parser].orEmpty().any { it.name == sym.name }
                if (!hasAlternatives) log.diag(abstractRuleWithoutAlternatives(ctx.ruleName.span, "${sym.parser.name}.${sym.name}"))
                return null
            }
            if (abstract != null && !types.isAssignable(abstract.returnType, sym.returnType)) {
                log.diag(alternativeTypeMismatch(ctx.returnType.span, "${sym.parser.name}.${sym.name}", abstract.returnType, sym.returnType))
            }
            currentParser = sym.parser
            withReceiver(sym.parser.type) {
                val optionTypes = ctx.parserRuleOption().map { withExpected(sym.returnType) { it.accept(this) } }
                if (optionTypes.all { it != null }) {
                    for ((option, type) in ctx.parserRuleOption().zip(optionTypes)) {
                        if (type != ErrorType && !types.isAssignable(sym.returnType, type!!)) {
                            log.diag(typeMismatch(option.span, sym.returnType, type))
                        }
                    }
                }
                else {
                    log.diag(canNotInferParseValue(ctx.ruleName.span))
                }
            }
            ctx.operatorSpec()?.let { checkOperator(ctx, it, sym, abstract) }
            currentParser = null
            return null
        }

        /**
         * `@infixLeft(10) -> l:expr '+' r:expr`: an operator of an abstract rule. Its sequence starts (infix,
         * postfix) and/or ends (prefix, infix) with the rule itself: the left operand and the operand.
         */
        private fun checkOperator(
            ctx: ForgeParser.ParseRuleDefContext,
            spec: ForgeParser.OperatorSpecContext,
            sym: ParserRuleSymbol,
            abstract: ParserRuleSymbol?,
        ) {
            val kind = spec.kind.text
            if (kind !in setOf("prefix", "infixLeft", "infixRight", "postfix")) {
                log.diag(invalidOperator(spec.kind.span, "unknown operator '$kind'; expected prefix, infixLeft, infixRight or postfix"))
                return
            }
            if (abstract == null) {
                log.diag(invalidOperator(spec.span, "operators are only allowed in abstract rules; declare 'rule ${sym.parser.name}.${sym.name}: ${sym.returnType}' first"))
                return
            }
            val sequence = ctx.parserRuleOption().singleOrNull()?.parseRule()?.parserAlternative()?.parserSequence()?.singleOrNull()
            if (sequence == null) {
                log.diag(invalidOperator(spec.span, "an operator consists of a single sequence"))
                return
            }
            val items = sequence.parserLabeled()
            fun isSelf(item: ForgeParser.ParserLabeledContext): Boolean {
                val postfix = item.parserPostfix()
                return item.predicate == null && postfix.op == null && postfix.count == null &&
                        postfix.atom.nonterminalRule()?.name?.text == sym.name
            }
            val left = kind == "infixLeft" || kind == "infixRight" || kind == "postfix"
            val right = kind != "postfix"
            val required = (if (left) 1 else 0) + (if (right) 1 else 0) + 1
            if (items.size < required || (left && !isSelf(items.first())) || (right && !isSelf(items.last()))) {
                val shape = when (kind) {
                    "prefix" -> "'-' e:${sym.name}"
                    "postfix" -> "e:${sym.name} '!'"
                    else -> "l:${sym.name} '+' r:${sym.name}"
                }
                log.diag(invalidOperator(spec.span, "a $kind operator has the shape $shape"))
                return
            }
            if (left) items.first().parserPostfix().atom.nonterminalRule().attach("role", "left")
            if (right) items.last().parserPostfix().atom.nonterminalRule().attach("role", "operand")
        }
        override fun visitIntValue(ctx: ForgeParser.IntValueContext): Type {
            // an int literal where a long is expected is one: val x: long = 1
            val expected = env.value.expected.let { if (it is OptionalType) it.elem else it }
            if (expected == LongType) return ctx.setType(LongType)
            return ctx.setType(IntType)
        }

        override fun visitLongValue(ctx: ForgeParser.LongValueContext): Type {
            return ctx.setType(LongType)
        }

        override fun visitFloatValue(ctx: ForgeParser.FloatValueContext): Type {
            return ctx.setType(FloatType)
        }

        override fun visitStringValue(ctx: ForgeParser.StringValueContext): Type {
            ctx.interpolated?.forEach { part -> if (part is ForgeParser.ExpressionContext) attribInterpolated(part) }
            return ctx.setType(StrType)
        }

        override fun visitBoolValue(ctx: ForgeParser.BoolValueContext): Type {
            return ctx.setType(BoolType)
        }

        override fun visitNullValue(ctx: ForgeParser.NullValueContext): Type {
            return ctx.setType(NullType)
        }

        override fun visitCast(ctx: ForgeParser.CastContext): Type {
            val type = chk.checkExists(ctx.ty.toType(), ctx.ty)
            val valueType = withExpected(type) { ctx.value.attribType() }
            if (type != ErrorType && valueType != ErrorType && !castable(valueType, type)) {
                log.diag(invalidCast(ctx.span, valueType, type))
            }
            return ctx.setType(type)
        }

        override fun visitNullCheck(ctx: ForgeParser.NullCheckContext): Type {
            val type = ctx.value.attribType()
            return ctx.setType(if (type is OptionalType) type.elem else type)
        }

        override fun visitEnclosed(ctx: ForgeParser.EnclosedContext): Type {
            return ctx.setType(ctx.expression().attribType())
        }

        // ---- operators: the builtin meaning for builtin types, else an `op fun` of the receiver ----------

        private fun numeric(t: Type) = t == IntType || t == LongType || t == FloatType

        private fun integral(t: Type) = t == IntType || t == LongType

        /**
         * Attributes [ctx] as a call of the operator function [name] of [receiver] with arguments of [args]
         * types, if there is one; returns its result type, or null if there is none.
         */
        private fun operator(ctx: ParserRuleContext, receiver: Type, name: String, args: List<Type>): Type? {
            if (receiver == ErrorType || receiver is OptionalType || receiver == NullType || args.any { it == ErrorType }) return null
            val fn = resolveMember(receiver, name, Kind.VAL, Kind.MTH) as? FunctionSymbol ?: return null
            if (!fn.isOperator) return null
            for ((param, arg) in fn.params.zip(args)) chk.checkAssignable(param.type, arg)
            ctx.attach("operator", fn)
            checkBounds(ctx, fn)
            return fn.returnType
        }

        override fun visitRelational(ctx: ForgeParser.RelationalContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            if (!(numeric(lhsTy) && numeric(rhsTy))) {
                operator(ctx, lhsTy, "compareTo", listOf(rhsTy))?.let { return ctx.setType(BoolType) }
            }
            chk.checkNumericOp(lhsTy, rhsTy, ctx.op.text)
            return ctx.setType(BoolType)
        }

        override fun visitInOp(ctx: ForgeParser.InOpContext): Type {
            val container = ctx.right.attribType()
            when (container) {
                is ListType -> withExpected(container.elem) { chk.checkAssignable(container.elem, ctx.left.attribType()) }
                is SetType -> withExpected(container.elem) { chk.checkAssignable(container.elem, ctx.left.attribType()) }
                is MapType -> withExpected(container.key) { chk.checkAssignable(container.key, ctx.left.attribType()) }
                StrType -> chk.checkAssignable(StrType, ctx.left.attribType())
                ErrorType -> ctx.left.attribType()
                else -> {
                    val element = ctx.left.attribType()
                    if (operator(ctx, container, "contains", listOf(element)) == null) {
                        log.diag(typeMismatch(ctx.right.span, listOf(ListType(ArbitraryType), SetType(ArbitraryType), MapType(ArbitraryType, ArbitraryType), StrType), container))
                    }
                }
            }
            return ctx.setType(BoolType)
        }

        override fun visitInstanceOf(ctx: ForgeParser.InstanceOfContext): Type {
            val type = chk.checkExists(ctx.ty.toType(), ctx.ty)
            val valueType = ctx.value.attribType()
            if (type != ErrorType && valueType != ErrorType && !castable(valueType, type)) {
                log.diag(invalidCast(ctx.span, valueType, type))
            }
            return ctx.setType(BoolType)
        }

        private fun castable(from: Type, to: Type): Boolean {
            val numeric = setOf(IntType, LongType, FloatType)
            if (from in numeric && to in numeric) return true
            val f = if (from is OptionalType) from.elem else from
            val t = if (to is OptionalType) to.elem else to
            return types.isAssignable(t, f) || types.isAssignable(f, t)
        }

        override fun visitListLiteral(ctx: ForgeParser.ListLiteralContext): Type {
            val elemTypes = ctx.expression().map { it.attribType() }
            val res = chk.checkAllAssignable(elemTypes, (env.value.expected as? ListType)?.elem)
            return ctx.setType(ListType(res))
        }

        override fun visitListComprehension(ctx: ForgeParser.ListComprehensionContext): Type {
            val iterable = ctx.iterable.attribType()
            if (iterable !is SetType) chk.checkAssignable(ListType(ArbitraryType), iterable)
            val iterType = when (iterable) {
                is ListType -> iterable.elem
                is SetType -> iterable.elem
                else -> ErrorType
            }

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

        override fun visitSetLiteral(ctx: ForgeParser.SetLiteralContext): Type {
            val expected = env.value.expected
            if (ctx.expression().isEmpty() && expected is MapType) {
                log.diag(emptyMapIsColon(ctx.span))
                return ctx.setType(ErrorType)
            }
            val elemTypes = ctx.expression().map { it.attribType() }
            val res = chk.checkAllAssignable(elemTypes, (expected as? SetType)?.elem)
            return ctx.setType(SetType(res))
        }

        override fun visitEmptyMapLiteral(ctx: ForgeParser.EmptyMapLiteralContext): Type {
            val expected = env.value.expected
            if (expected is MapType) return ctx.setType(expected)
            log.diag(listTypeIsAmbiguous(ctx.span))
            return ctx.setType(MapType(ArbitraryType, ArbitraryType))
        }

        override fun visitRangeLiteral(ctx: ForgeParser.RangeLiteralContext): Type {
            withExpected(IntType) {
                chk.checkAssignable(IntType, ctx.lo.attribType())
                chk.checkAssignable(IntType, ctx.hi.attribType())
            }
            return ctx.setType(ListType(IntType))
        }

        override fun visitAddSub(ctx: ForgeParser.AddSubContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            val isAdd = ctx.PLUS() != null
            val builtin = (numeric(lhsTy) && numeric(rhsTy)) || (isAdd && lhsTy == StrType && rhsTy == StrType)
            if (!builtin) operator(ctx, lhsTy, if (isAdd) "plus" else "minus", listOf(rhsTy))?.let { return ctx.setType(it) }
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
            val name = if (isMod) "rem" else if (ctx.STAR() != null) "times" else "div"
            val builtin = if (isMod) integral(lhsTy) && integral(rhsTy) else numeric(lhsTy) && numeric(rhsTy)
            if (!builtin) operator(ctx, lhsTy, name, listOf(rhsTy))?.let { return ctx.setType(it) }
            return ctx.setType(if (isMod) {
                if (builtin) chk.numericResult(lhsTy, rhsTy)!! else {
                    chk.checkAssignable(IntType, lhsTy)
                    chk.checkAssignable(IntType, rhsTy)
                    IntType
                }
            } else {
                chk.checkNumericOp(lhsTy, rhsTy, ctx.STAR()?.let { "*" } ?: "/")
            })
        }

        override fun visitShift(ctx: ForgeParser.ShiftContext): Type {
            // the operator is made of adjacent tokens: `a > > b` is no shift
            val tokens = ctx.LT() + ctx.GT()
            val sorted = tokens.map { it.symbol }.sortedBy { it.startIndex }
            if (sorted.zipWithNext().any { (a, b) -> a.stopIndex + 1 != b.startIndex }) {
                log.diag(invalidOperatorFunction(ctx.span, "a shift operator is written without spaces: << >> >>>"))
            }
            val lhsTy = ctx.left.attribType()
            val rhsTy = ctx.right.attribType()
            val name = when {
                ctx.LT().isNotEmpty() -> "shl"
                ctx.GT().size == 3 -> "ushr"
                else -> "shr"
            }
            if (!(integral(lhsTy) && rhsTy == IntType)) operator(ctx, lhsTy, name, listOf(rhsTy))?.let { return ctx.setType(it) }
            if (lhsTy == LongType) {
                chk.checkAssignable(IntType, rhsTy)
                return ctx.setType(LongType)
            }
            chk.checkAssignable(IntType, lhsTy)
            chk.checkAssignable(IntType, rhsTy)
            return ctx.setType(IntType)
        }

        override fun visitConcat(ctx: ForgeParser.ConcatContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = withExpected(lhsTy) { ctx.right.attribType() }
            val builtin = (lhsTy is ListType && rhsTy is ListType) || (lhsTy is MapType && rhsTy is MapType) || (lhsTy is SetType && rhsTy is SetType)
            if (!builtin) operator(ctx, lhsTy, "concat", listOf(rhsTy))?.let { return ctx.setType(it) }
            return ctx.setType(chk.checkConcatOp(lhsTy, rhsTy))
        }

        override fun visitContainerAccess(ctx: ForgeParser.ContainerAccessContext): Type {
            val lhsTy = ctx.container.attribType()
            val prevEnv = env
            if (lhsTy is MapType) env = env.copy(value = env.value.copy(expected = lhsTy.key))
            val rhsTy = ctx.key.attribType()
            this.env = prevEnv
            if (!(lhsTy is ListType || lhsTy is MapType || lhsTy == StrType)) {
                operator(ctx, lhsTy, "get", listOf(rhsTy))?.let { return ctx.setType(it) }
            }
            return ctx.setType(chk.checkContainerAccess(lhsTy, rhsTy))
        }

        override fun visitSliceAccess(ctx: ForgeParser.SliceAccessContext): Type {
            val lhsTy = ctx.container.attribType()
            val bounds = listOfNotNull(ctx.lo, ctx.hi).map { withExpected(IntType) { it.attribType() } }
            if (lhsTy is ListType || lhsTy == StrType) {
                bounds.forEach { chk.checkAssignable(IntType, it) }
                return ctx.setType(lhsTy)
            }
            // an omitted bound is null
            val args = listOf(ctx.lo, ctx.hi).map { it?.type ?: NullType }
            operator(ctx, lhsTy, "slice", args)?.let { return ctx.setType(it) }
            if (lhsTy != ErrorType) log.diag(typeMismatch(ctx.container.span, listOf(ListType(ArbitraryType), StrType), lhsTy))
            return ctx.setType(ErrorType)
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
            if (!numeric(exprTy)) operator(ctx, exprTy, "neg", emptyList())?.let { return ctx.setType(it) }
            return ctx.setType(chk.checkNegation(exprTy))
        }

        override fun visitInversion(ctx: ForgeParser.InversionContext): Type {
            val exprTy = ctx.expression().attribType()
            if (exprTy != IntType && exprTy != BoolType) operator(ctx, exprTy, "not", emptyList())?.let { return ctx.setType(it) }
            return ctx.setType(chk.checkInversion(exprTy))
        }

        override fun visitEquality(ctx: ForgeParser.EqualityContext): Type {
            val expLhs = ctx.left.attribType()
            val expRhs = withExpected(expLhs) { ctx.right.attribType() }
            if (listOf(expLhs, expRhs).any { (if (it is OptionalType) it.elem else it) is FunctionType }) log.diag(functionsNotComparable(ctx.span))
            chk.checkShareCommonSuperType(expLhs, expRhs, null)
            return ctx.setType(BoolType)
        }

        override fun visitLogicalAnd(ctx: ForgeParser.LogicalAndContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = withExpected(lhsTy) { ctx.right.attribType() }
            chk.checkBoolOrInt(lhsTy)
            if (lhsTy is ErrorType) return ctx.setType(chk.checkBoolOrInt(rhsTy))
            chk.checkAssignable(lhsTy, rhsTy)
            return ctx.setType(lhsTy)
        }

        override fun visitLogicalOr(ctx: ForgeParser.LogicalOrContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = withExpected(lhsTy) { ctx.right.attribType() }
            chk.checkBoolOrInt(lhsTy)
            if (lhsTy is ErrorType) return ctx.setType(chk.checkBoolOrInt(rhsTy))
            chk.checkAssignable(lhsTy, rhsTy)
            return ctx.setType(lhsTy)
        }

        override fun visitLogicalXor(ctx: ForgeParser.LogicalXorContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = withExpected(lhsTy) { ctx.right.attribType() }
            chk.checkBoolOrInt(lhsTy)
            if (lhsTy is ErrorType) return ctx.setType(chk.checkBoolOrInt(rhsTy))
            chk.checkAssignable(lhsTy, rhsTy)
            return ctx.setType(lhsTy)
        }

        override fun visitIand(ctx: ForgeParser.IandContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = withExpected(lhsTy) { ctx.right.attribType() }
            if (lhsTy == LongType || rhsTy == LongType) {
                chk.checkAssignable(LongType, lhsTy)
                chk.checkAssignable(LongType, rhsTy)
                return ctx.setType(LongType)
            }
            chk.checkAssignable(IntType, lhsTy)
            chk.checkAssignable(IntType, rhsTy)
           return ctx.setType(IntType)
        }

        override fun visitIor(ctx: ForgeParser.IorContext): Type {
            val lhsTy = ctx.left.attribType()
            val rhsTy = withExpected(lhsTy) { ctx.right.attribType() }
            if (lhsTy == LongType || rhsTy == LongType) {
                chk.checkAssignable(LongType, lhsTy)
                chk.checkAssignable(LongType, rhsTy)
                return ctx.setType(LongType)
            }
            chk.checkAssignable(IntType, lhsTy)
            chk.checkAssignable(IntType, rhsTy)
            return ctx.setType(IntType)
        }

        override fun visitIdentifier(ctx: ForgeParser.IdentifierContext): Type =
            attribIdent(ctx, setOf(Kind.VAL))

        override fun visitFieldAccess(ctx: ForgeParser.FieldAccessContext): Type {
            val recv = ctx.receiver
            val recvTy = asReceiver { recv.attribOwner(setOf(Kind.VAL, Kind.TYP)) }
            if (recvTy == ErrorType) return ctx.setType(ErrorType)

            val fieldName = ctx.name.text

            val sym = resolveMember(recvTy, fieldName, recv.receiverKind(), Kind.VAL)

            if (sym is InhSymbol && recv !is ForgeParser.ThisExprContext) {
                log.diag(invalidExternalInhAttribAccess(ctx.span))
            }
            if (sym is InhSymbol && recvTy == ComplexType(VERTEX)) {
                log.diag(inhOnVertex(ctx.span, fieldName))
            }

            if (sym == null) {
                log.diag(attributeNotFound(ctx.name.span, recvTy.toString(), fieldName))
                return ctx.setType(ErrorType)
            }
            ctx.setSym(sym)
            checkBounds(ctx, sym)
            // an item of a generic enum without fields is one of Nothing: Option.NONE is an Option<Nothing>
            if (sym is EnumItemSymbol && sym.typeParams.isNotEmpty()) {
                return ctx.setType(ComplexType(sym.enumType, sym.typeParams.map { NothingType }))
            }
            // a property (`val`) is read like a field: its value is what it returns
            return ctx.setType(if (sym is FunctionSymbol) sym.returnType else sym.type)
        }

        override fun visitFunctionCall(ctx: ForgeParser.FunctionCallContext): Type {
            val name = ctx.name.text
            if (env.value.inFlow && lookupLocal(name) == null) {
                attribFlowCall(ctx)?.let { return it }
            }
            // a variable or constant whose value is a function: f(x)
            val value = lookupLocal(name) ?: symTab.constants[name]?.also { attribConst(it) }
            if (value != null) {
                ctx.attach("callee", value)
                return ctx.setType(attribValueCall(ctx, value.type, ctx.expression()))
            }
            var sym = chk.resolveFunction(name) ?: run {
                ctx.expression().forEach { it.attribType() }
                return ctx.setType(ErrorType)
            }
            // a generic function: its type parameters are what the arguments are
            if (sym is FunctionSymbol && sym.typeParams.isNotEmpty()) {
                val bindings = mutableMapOf<String, Type>()
                inferable?.let { unifyResult(sym.returnType, it, bindings) }
                val argTypes = attribGenericArgs(ctx.expression(), sym.params.map { it.type }, bindings, sym.typeParams.toSet())
                val use = sym.instantiate(bindings)
                ctx.setSym(use)
                if (argTypes.size < use.params.size) log.diag(tooFewArguments(ctx.span))
                if (argTypes.size > use.params.size) log.diag(tooManyArguments(ctx.span))
                use.params.zip(argTypes).forEach { (p, t) -> chk.checkAssignable(p.type, t) }
                checkBounds(ctx, use)
                return ctx.setType(use.returnType)
            }
            // a generic record: its type arguments are what the fields are
            if (sym is RecordSymbol && sym.typeParams.isNotEmpty()) {
                ctx.setSym(sym)
                return ctx.setType(attribGenericConstructor(ctx, sym.name, sym.typeParams, sym.params.map { it.type }, ctx.expression()))
            }
            ctx.setSym(sym)
            if (sym is RecordSymbol && sym.opaque) {
                log.diag(opaqueType(ctx.name.span, sym.name))
            }
            val paramTypes = sym.params.map { it.type }
            val args = ctx.expression()
            if (args.size < paramTypes.size) {
                log.diag(tooFewArguments(ctx.span))
            }
            if (args.size > paramTypes.size) {
                log.diag(tooManyArguments(ctx.span))
            }
            for ((i, arg) in args.withIndex()) {
                val expected = paramTypes.getOrNull(i)
                if (expected == null) {
                    arg.attribType()
                    continue
                }
                withExpected(expected) {
                    chk.checkAssignable(expected, arg.attribType())
                }
            }
            return ctx.setType(sym.returnType)
        }

        /**
         * The arguments of a call of a generic function, binding its type parameters. Arguments whose type
         * comes from what is expected (`[]`, `{}`, `{:}`, `null`) are attributed last, once the others bound
         * what they can.
         */
        private fun attribGenericArgs(args: List<ForgeParser.ExpressionContext>, params: List<Type>, bindings: MutableMap<String, Type>, typeParams: Set<String>): List<Type> {
            val types = arrayOfNulls<Type>(args.size)
            fun attrib(i: Int) {
                val declared = params.getOrNull(i)
                val param = declared?.substitute(bindings)
                val unbound = declared?.typeVars().orEmpty().filter { it in typeParams && it !in bindings }.toSet()
                // a lambda takes the types of its parameters from what is expected, also if its result is to be inferred
                val lambda = args[i].isFunctionValue()
                types[i] = when {
                    param == null || (unbound.isNotEmpty() && !lambda) -> withoutExpected { args[i].attribType() }
                    else -> withPending(unbound) { withExpected(param) { args[i].attribType() } }
                }
                declared?.let { unify(it, types[i]!!, bindings) }
            }
            val late = args.indices.filter { args[it].text in setOf("[]", "{}", "{:}", "null") }
            // lambdas last: the other arguments bind the types of their parameters
            val lambdas = args.indices.filter { it !in late && args[it].isFunctionValue() }
            args.indices.filter { it !in late && it !in lambdas }.forEach(::attrib)
            late.forEach(::attrib)
            lambdas.forEach(::attrib)
            return types.map { it!! }
        }

        /** Whether an argument is a lambda or a function used as a value, which needs to know what is expected. */
        private fun ForgeParser.ExpressionContext.isFunctionValue(): Boolean = when (this) {
            is ForgeParser.LambdaContext -> true
            is ForgeParser.EnclosedContext -> expression().isFunctionValue()
            is ForgeParser.IdentifierContext -> lookupLocal(IDENT().text) == null && symTab.constants[IDENT().text] == null &&
                symTab.functions[IDENT().text] != null
            else -> false
        }

        /** Binds type variables of the result of a generic function from what is expected of the call. */
        private fun unifyResult(result: Type, expected: Type, bindings: MutableMap<String, Type>) {
            if (expected == ErrorType || !result.hasTypeVars()) return
            unify(result, expected, bindings)
        }

        /**
         * A constructor of a generic record or enum item: its type arguments are what the arguments are, or what
         * is expected; type parameters bound by neither are `Nothing` (an `Option.NONE` is an `Option<Nothing>`).
         */
        private fun attribGenericConstructor(
            ctx: ParserRuleContext,
            name: String,
            typeParams: List<String>,
            params: List<Type>,
            args: List<ForgeParser.ExpressionContext>,
        ): Type {
            val bindings = mutableMapOf<String, Type>()
            val expected = inferable.let { if (it is OptionalType) it.elem else it }
            if (expected is ComplexType && expected.name == name && expected.args.size == typeParams.size) {
                typeParams.zip(expected.args).forEach { (p, a) -> if (!a.hasTypeVars() || a is TypeVar) bindings[p] = a }
            }
            val argTypes = attribGenericArgs(args, params, bindings, typeParams.toSet())
            if (argTypes.size < params.size) log.diag(tooFewArguments(ctx.span))
            if (argTypes.size > params.size) log.diag(tooManyArguments(ctx.span))
            val actual = typeParams.associateWith { bindings[it] ?: NothingType }
            params.zip(argTypes).forEach { (p, t) -> chk.checkAssignable(p.substitute(actual), t) }
            return ComplexType(name, typeParams.map { actual.getValue(it) })
        }

        /** `entry(x)`, `sequence(xs)`, `bound(port)` and `port(child)` inside flows; `null` for other calls. */
        private fun attribFlowCall(ctx: ForgeParser.FunctionCallContext): Type? {
            val name = ctx.name.text
            val args = ctx.expression()
            fun arity(n: Int) {
                if (args.size < n) log.diag(tooFewArguments(ctx.span))
                if (args.size > n) log.diag(tooManyArguments(ctx.span))
            }
            val port = symTab.ports[name]
            if (port != null) {
                arity(1)
                args.firstOrNull()?.let { checkFlowOperand(it, it.attribType(), FlowRole.CHILD) }
                ctx.attach("port", port)
                return ctx.setType(PortRefType)
            }
            when (name) {
                "entry" -> {
                    arity(1)
                    args.firstOrNull()?.let { checkFlowOperand(it, it.attribType(), FlowRole.TARGET) }
                }
                "sequence" -> {
                    arity(1)
                    args.firstOrNull()?.let {
                        val type = it.attribType()
                        if (type != ErrorType && !(type is ListType && isNodeType(type.elem))) {
                            log.diag(typeMismatch(it.span, ListType(ComplexType(VERTEX)), type))
                        }
                    }
                }
                "bound" -> {
                    arity(1)
                    args.firstOrNull()?.let { chk.checkAssignable(PortType, it.attribType()) }
                    ctx.attach("flowBuiltin", name)
                    return ctx.setType(BoolType)
                }
                else -> return null
            }
            ctx.attach("flowBuiltin", name)
            return ctx.setType(NullType)
        }

        override fun visitMethodCall(ctx: ForgeParser.MethodCallContext): Type {
            val recv = ctx.receiver
            val recvTy = asReceiver { recv.attribOwner(setOf(Kind.VAL, Kind.TYP)) }
            val args = ctx.expression().drop(1)

            if (recvTy == ErrorType) {
                args.forEach { it.attribType() }
                return ctx.setType(ErrorType)
            }

            val methodName = ctx.name.text
            var sym = resolveMember(recvTy, methodName, recv.receiverKind(), Kind.MTH)

            if (sym == null) {
                // a field or property whose value is a function: this.handler(x)
                val field = if (recv.receiverKind() == Kind.VAL) resolveMember(recvTy, methodName, Kind.VAL, Kind.VAL) else null
                val fieldType = when (field) {
                    is FunctionSymbol -> field.returnType
                    null -> null
                    else -> field.type
                }
                if (field != null && fieldType.let { (if (it is OptionalType) it.elem else it) is FunctionType }) {
                    if (field is InhSymbol && recv !is ForgeParser.ThisExprContext) log.diag(invalidExternalInhAttribAccess(ctx.span))
                    ctx.attach("field", field)
                    return ctx.setType(attribValueCall(ctx, fieldType!!, args))
                }
                args.forEach { it.attribType() }
                log.diag(methodNotFound(ctx.name.span, recvTy.toString(), methodName))
                return ctx.setType(ErrorType)
            }
            // an item of a generic enum: its type arguments are what its fields are
            if (sym is EnumItemSymbol && sym.typeParams.isNotEmpty()) {
                ctx.setSym(sym)
                return ctx.setType(attribGenericConstructor(ctx, sym.enumType, sym.typeParams, sym.parameters.map { it.second }, args))
            }
            val argTypes: List<Type>
            if (sym is FunctionSymbol && sym.declaration.typeParams.isNotEmpty()) {
                // a generic method: what the receiver does not bind, the arguments do
                val declaration = sym.declaration
                val bindings = mutableMapOf<String, Type>()
                unify(declaration.receiver!!.type, if (recvTy is OptionalType) recvTy.elem else recvTy, bindings)
                inferable?.let { unifyResult(declaration.returnType, it, bindings) }
                argTypes = attribGenericArgs(args, declaration.params.map { it.type }, bindings, declaration.typeParams.toSet())
                sym = declaration.instantiate(bindings)
            } else {
                val expected = (sym.type as? CallableType)?.parameters.orEmpty()
                argTypes = args.mapIndexed { i, arg ->
                    val e = expected.getOrNull(i)
                    if (e == null) arg.attribType() else withExpected(e) { arg.attribType() }
                }
            }
            ctx.setSym(sym)
            checkBounds(ctx, sym)

            val symType = sym.type
            if (symType !is CallableType) {
                log.diag(typeMismatch(ctx.name.span, FunctionType(argTypes, ArbitraryType), symType))
                return ctx.setType(ErrorType)
            }

            if (argTypes.size < symType.parameters.size) {
                log.diag(tooFewArguments(ctx.span))
            }
            if (argTypes.size > symType.parameters.size) {
                log.diag(tooManyArguments(ctx.span))
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
            // the fields of an item of a generic enum are of the type arguments of the matched value
            val matchedType = env.value.expected.let { if (it is OptionalType) it.elem else it }
            val bindings = if (matchedType == null) emptyMap() else resolve.typeArguments(sym.typeParams, matchedType)
            val paramTypes = sym.parameters.map { it.second.substitute(bindings) }
            ctx.attach("fieldTypes", paramTypes)
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
            if (sym.typeParams.isNotEmpty()) {
                return ctx.setType(if (bindings.isNotEmpty()) matchedType!! else ComplexType(recv.name, sym.typeParams.map { NothingType }))
            }
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

            val res = if (ctx.patternArg().isEmpty()) {
                (env.value.expected as? ListType)?.elem ?: ArbitraryType
            } else if (patternTypes.isNotEmpty()) {
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
                // the labels of this option, for the generated code
                ctx.attach("labels", env.value.scope.members.filterIsInstance<VarSymbol>())
                return if (ctx.producer != null) {
                    ctx.setType(ctx.producer.attribType())
                } else {
                    ctx.setNullableType(if (ty is TerminalRuleProduction) ty.actual else ty)
                }
            }
            throw AssertionError("Should not reach here")
        }

        override fun visitParseRule(ctx: ForgeParser.ParseRuleContext): Type? {
            return ctx.setNullableType(ctx.parserAlternative().accept(this))
        }

        override fun visitParserAlternative(ctx: ForgeParser.ParserAlternativeContext): Type? {
            val alternatives = ctx.parserSequence().map { it.accept(this) }.map { if (it is TerminalRuleProduction) it.actual else it }
            if (alternatives.any { it is ErrorType }) return ErrorType
            if (alternatives.size == 1) return alternatives.first()
            if (alternatives.any { it == null }) return null
            return alternatives.reduce { a, b -> a?.let { types.sharedSuperType(it, b!!) } ?: return null }
        }

        /**
         * The value of a sequence: its only unlabeled element that has a value (`'(' expr ')'`), or the text of
         * a single terminal, or the texts of a sequence of terminals. Otherwise a producer is needed.
         */
        override fun visitParserSequence(ctx: ForgeParser.ParserSequenceContext): Type? {
            val items = ctx.parserLabeled()
            val types = items.map { it.accept(this) }
            if (types.any { it is ErrorType }) return ErrorType
            val unlabeled = items.indices.filter { items[it].label == null && items[it].predicate == null }
            val values = unlabeled.filter { types[it] !is TerminalRuleProduction }
            if (values.size == 1) {
                ctx.attach("value", values.single())
                return ctx.setNullableType(types[values.single()])
            }
            if (values.isEmpty() && unlabeled.size == items.size) {
                val terminals = unlabeled.filter { (types[it] as TerminalRuleProduction).actual == StrType }
                if (terminals.size == 1 && items.size == 1) {
                    ctx.attach("value", 0)
                    return ctx.setType(StrType)
                }
                if (terminals.size == items.size && items.size > 1) {
                    ctx.attach("value", -1)
                    return ctx.setType(ListType(StrType))
                }
            }
            return ctx.setNullableType(null)
        }

        override fun visitParserPostfix(ctx: ForgeParser.ParserPostfixContext): Type? {
            var atomType = ctx.atom.accept(this)
            if (atomType is ErrorType) return ErrorType
            if (ctx.count != null) {
                withExpected(IntType) { chk.checkAssignable(IntType, ctx.count.attribType()) }
            }
            if (ctx.op == null && ctx.count == null) return atomType
            if (atomType is TerminalRuleProduction) atomType = atomType.actual
            if (atomType == null) return null
            return when {
                ctx.QUESTIONMARK() != null -> OptionalType(atomType)
                else -> ListType(atomType)
            }
        }

        override fun visitParserAtom(ctx: ForgeParser.ParserAtomContext): Type? {
            ctx.terminalRule()?.let { return it.accept(this) }
            ctx.bitsRule()?.let { return it.accept(this) }
            ctx.builtinRule()?.let { return it.accept(this) }
            ctx.nonterminalRule()?.let { return it.accept(this) }
            return ctx.parseRule().accept(this)
        }

        override fun visitTerminalRule(ctx: ForgeParser.TerminalRuleContext): Type {
            val text = ctx.REGEX_STRING().text
            val regex = text.substring(1, text.length - 1)
            try {
                java.util.regex.Pattern.compile(regex)
            } catch (e: java.util.regex.PatternSyntaxException) {
                log.diag(regexMatchedLiterally(ctx.span, regex, e.description))
            }
            ctx.setType(StrType)
            return TerminalRuleProduction(StrType)
        }

        override fun visitBitsRule(ctx: ForgeParser.BitsRuleContext): Type {
            if (currentParser?.binary != true) log.diag(binaryOnly(ctx.span, "bit patterns"))
            ctx.setType(StrType)
            return TerminalRuleProduction(StrType)
        }

        override fun visitBuiltinRule(ctx: ForgeParser.BuiltinRuleContext): Type {
            val name = ctx.name.text
            if (currentParser?.binary != true) log.diag(binaryOnly(ctx.span, "'@$name'"))
            val (takesArgument, type) = when (name) {
                "u1", "u2", "u4" -> false to IntType
                "bit" -> false to BoolType
                "uint", "sint" -> true to IntType
                "bytes" -> true to ListType(IntType)
                else -> {
                    log.diag(unknownPrimitive(ctx.name.span, name))
                    ctx.arg?.attribType()
                    return ctx.setType(ErrorType)
                }
            }
            if (takesArgument && ctx.arg == null) log.diag(tooFewArguments(ctx.span))
            if (!takesArgument && ctx.arg != null) log.diag(tooManyArguments(ctx.span))
            ctx.arg?.let { withExpected(IntType) { chk.checkAssignable(IntType, it.attribType()) } }
            return ctx.setType(type)
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
            if (ctx.predicate != null) {
                if (ctx.label != null) log.diag(ruleLabelingNotAllowedHere(ctx.label.span))
                return if (type == ErrorType) type else TerminalRuleProduction(NullType)
            }
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
            return type
        }

        // ---- flows ------------------------------------------------------------------------------

        private fun isNodeType(type: Type): Boolean = type is ComplexType && resolve.resolve(type) is NodeSymbol

        /**
         * Checks an operand of a flow statement: children and vertices are nodes (or `Vertex`), sources may be
         * ports of children (`onTrue(cond)`), targets may be ports of the node itself (`next`).
         */
        private fun checkFlowOperand(ctx: ParserRuleContext, type: Type, role: FlowRole) {
            when {
                type == ErrorType -> {}
                type == PortType -> if (role != FlowRole.TARGET) log.diag(ownPortNotASource(ctx.span))
                type == PortRefType -> if (role != FlowRole.SOURCE) log.diag(portOfChildNotATarget(ctx.span))
                type is OptionalType && isNodeType(type.elem) -> log.diag(typeMismatch(ctx.span, type.elem, type))
                !isNodeType(type) -> log.diag(notAFlowTarget(ctx.span, type))
            }
        }

        override fun visitFlowDef(ctx: ForgeParser.FlowDefContext): Type? {
            val sym = ctx.symbol ?: return null
            withFlow(sym.node.type) {
                val fields = (sym.node as? ConcreteNodeSymbol)?.fields ?: emptyList()
                for ((binder, field) in ctx.flowBinder().zip(fields)) {
                    val name = binder.name ?: continue
                    val local = VarSymbol(name.text, field.type, name.span)
                    env.value.scope.enter(local)
                    binder.attach("local", local)
                }
                ctx.flowBlock().accept(this)
            }
            return null
        }

        override fun visitFlowBlock(ctx: ForgeParser.FlowBlockContext): Type? {
            withChildScope {
                ctx.flowStmt().forEach { it.accept(this) }
            }
            return null
        }

        override fun visitFlowConnect(ctx: ForgeParser.FlowConnectContext): Type? {
            checkFlowOperand(ctx.source, ctx.source.attribType(), FlowRole.SOURCE)
            checkFlowOperand(ctx.target, ctx.target.attribType(), FlowRole.TARGET)
            if (ctx.label != null) {
                withExpected(StrType) { chk.checkAssignable(StrType, ctx.label.attribType()) }
            }
            return null
        }

        override fun visitFlowVertex(ctx: ForgeParser.FlowVertexContext): Type? {
            if (ctx.label != null) {
                withExpected(StrType) { chk.checkAssignable(StrType, ctx.label.attribType()) }
            }
            val local = VarSymbol(ctx.name.text, ComplexType(VERTEX), ctx.name.span)
            env.value.scope.enter(local)
            ctx.attach("local", local)
            return null
        }

        override fun visitFlowIf(ctx: ForgeParser.FlowIfContext): Type? {
            chk.checkAssignable(BoolType, ctx.cond.attribType())
            ctx.then.accept(this)
            ctx.alt?.accept(this)
            return null
        }

        override fun visitFlowFor(ctx: ForgeParser.FlowForContext): Type? {
            val iterable = ctx.iterable.attribType()
            chk.checkAssignable(ListType(ArbitraryType), iterable)
            val elem = (iterable as? ListType)?.elem ?: ErrorType
            withChildScope {
                val local = VarSymbol(ctx.name.text, elem, ctx.name.span)
                env.value.scope.enter(local)
                ctx.attach("local", local)
                ctx.flowBlock().accept(this)
            }
            return null
        }

        override fun visitFlowCall(ctx: ForgeParser.FlowCallContext): Type? {
            val expr = ctx.expression()
            expr.attribType()
            if (expr !is ForgeParser.FunctionCallContext || expr.flowBuiltin !in setOf("entry", "sequence")) {
                log.diag(invalidFlowStatement(expr.span))
            }
            return null
        }


        // ---- functions as values ----------------------------------------------------------------

        /** A call of a function value of type [callee] with [args]; returns the type of the result. */
        private fun attribValueCall(ctx: ParserRuleContext, callee: Type, args: List<ForgeParser.ExpressionContext>): Type {
            val fn = if (callee is OptionalType) {
                chk.checkAssignable(callee.elem, callee)
                callee.elem
            } else callee
            if (fn == ErrorType) {
                args.forEach { it.attribType() }
                return ErrorType
            }
            if (fn !is FunctionType) {
                args.forEach { it.attribType() }
                log.diag(notAFunction(ctx.span, fn))
                return ErrorType
            }
            if (args.size < fn.parameters.size) log.diag(tooFewArguments(ctx.span))
            if (args.size > fn.parameters.size) log.diag(tooManyArguments(ctx.span))
            for ((i, arg) in args.withIndex()) {
                val expected = fn.parameters.getOrNull(i)
                if (expected == null) arg.attribType() else withExpected(expected) { chk.checkAssignable(expected, arg.attribType()) }
            }
            return fn.returnType
        }

        override fun visitValueCall(ctx: ForgeParser.ValueCallContext): Type {
            val callee = withoutExpected { ctx.callee.attribType() }
            return ctx.setType(attribValueCall(ctx, callee, ctx.expression().drop(1)))
        }

        override fun visitLambda(ctx: ForgeParser.LambdaContext): Type {
            val expected = env.value.expected.let { if (it is OptionalType) it.elem else it } as? FunctionType
            val params = ctx.lambdaParam()
            val fits = expected != null && expected.parameters.size == params.size
            val paramTypes = params.mapIndexed { i, p ->
                val declared = p.ty?.let { chk.checkExists(it.toType().withTypeParams(currentTypeParams), it) }
                declared ?: expected?.parameters?.getOrNull(i)?.takeIf { fits && it.known() } ?: run {
                    log.diag(canNotInferLambdaParameter(p.span, p.name?.text ?: "_"))
                    ErrorType
                }
            }
            var result: Type = ErrorType
            withPending(emptySet()) { withChildScope {
                val symbols = params.zip(paramTypes).map { (p, t) ->
                    VarSymbol(p.name?.text ?: "_", t, p.span).also { if (p.name != null) env.value.scope.enter(it) }
                }
                ctx.attach("params", symbols)
                val expectedResult = expected?.returnType?.takeIf { fits && it.known() }
                result = if (expectedResult != null) {
                    val body = withExpected(expectedResult) { ctx.body.attribType() }
                    if (types.isAssignable(expectedResult, body)) expectedResult else body
                } else {
                    withoutExpected { ctx.body.attribType() }
                }
            } }
            return ctx.setType(FunctionType(paramTypes, result))
        }

        /** A function used as a value (`xs.map(double)`); a generic one takes its type arguments from what is expected. */
        private fun functionValue(ctx: ForgeParser.IdentifierContext, sym: FunctionSymbol): Type {
            if (sym.isOperator || sym.receiver != null) {
                log.diag(canNotUseAsValue(ctx.IDENT().span, sym.name, "it is a method"))
                return ctx.setType(ErrorType)
            }
            var use = sym
            if (sym.typeParams.isNotEmpty()) {
                val expected = env.value.expected.let { if (it is OptionalType) it.elem else it }
                val bindings = mutableMapOf<String, Type>()
                if (expected is FunctionType) unify(sym.type, expected, bindings)
                if (!sym.typeParams.all { it in bindings }) {
                    log.diag(canNotUseAsValue(ctx.IDENT().span, sym.name, "its type parameters are not known here"))
                    return ctx.setType(ErrorType)
                }
                use = sym.instantiate(bindings)
                checkBounds(ctx, use)
            }
            ctx.setSym(use)
            return ctx.setType(use.type)
        }

        // ---- local bindings ---------------------------------------------------------------------

        /**
         * `body where x = 1, y: int = x + 1`: the bindings are visible in the body and in each other, in any order;
         * their values are computed in the order of their dependencies.
         */
        override fun visitWhereExpr(ctx: ForgeParser.WhereExprContext): Type {
            val bindings = ctx.binding()
            val names = mutableMapOf<String, ForgeParser.BindingContext>()
            for (b in bindings) {
                for (n in b.boundNames()) {
                    if (names.put(n.text, b) != null) log.diag(bindingAlreadyDefined(n.span, n.text))
                }
            }
            // dependencies: the names of other bindings a value uses
            val deps = bindings.associateWith { b -> referencedNames(b.value).mapNotNull { names[it] }.filter { it !== b }.toSet() }
            val order = mutableListOf<ForgeParser.BindingContext>()
            val state = mutableMapOf<ForgeParser.BindingContext, Int>()
            fun visit(b: ForgeParser.BindingContext, path: List<ForgeParser.BindingContext>) {
                when (state[b]) {
                    2 -> return
                    1 -> {
                        val cycle = path.dropWhile { it !== b } + b
                        log.diag(bindingDependsOnItself(b.span, cycle.joinToString(" -> ") { c -> c.boundNames().joinToString(", ") { it.text } }))
                        return
                    }
                }
                state[b] = 1
                deps.getValue(b).forEach { visit(it, path + b) }
                state[b] = 2
                order += b
            }
            bindings.forEach { visit(it, emptyList()) }
            var result: Type = ErrorType
            withChildScope {
                for (b in order) {
                    val declared = b.ty?.let { chk.checkExists(it.toType().withTypeParams(currentTypeParams), it) }
                    val valueType = if (declared != null) withExpected(declared) { b.value.attribType() } else withoutExpected { b.value.attribType() }
                    if (declared != null) chk.checkAssignable(declared, valueType)
                    val type = declared ?: valueType
                    val symbols = if (b.name != null) {
                        listOf(VarSymbol(b.name.text, type, b.name.span))
                    } else {
                        val elems = (type as? TupleType)?.elems
                        if (type != ErrorType && (elems == null || elems.size != b.names.size)) {
                            log.diag(invalidDestructuring(b.value.span, "'$type' is not a tuple of ${b.names.size} values"))
                        }
                        b.names.mapIndexed { i, n -> VarSymbol(n.text, elems?.getOrNull(i) ?: ErrorType, n.span) }
                    }
                    symbols.forEach { env.value.scope.enter(it) }
                    b.attach("symbols", symbols)
                }
                ctx.attach("order", order.toList())
                result = ctx.expr.attribType()
            }
            return ctx.setType(result)
        }

        private fun ForgeParser.BindingContext.boundNames(): List<org.antlr.v4.runtime.Token> = if (name != null) listOf(name) else names

        /** The names an expression uses as identifiers or calls. */
        private fun referencedNames(tree: org.antlr.v4.runtime.tree.ParseTree): Set<String> = buildSet {
            fun walk(t: org.antlr.v4.runtime.tree.ParseTree) {
                when (t) {
                    is ForgeParser.IdentifierContext -> add(t.IDENT().text)
                    is ForgeParser.FunctionCallContext -> add(t.name.text)
                }
                for (i in 0 until t.childCount) walk(t.getChild(i))
            }
            walk(tree)
        }

        // ---- tuples -----------------------------------------------------------------------------

        override fun visitTupleLiteral(ctx: ForgeParser.TupleLiteralContext): Type {
            val expected = (env.value.expected.let { if (it is OptionalType) it.elem else it } as? TupleType)
                ?.takeIf { it.elems.size == ctx.expression().size }
            val elems = ctx.expression().mapIndexed { i, e ->
                val x = expected?.elems?.get(i)
                if (x != null) withExpected(x) { e.attribType() } else withoutExpected { e.attribType() }
            }
            return ctx.setType(TupleType(elems))
        }

        override fun visitTupleAccess(ctx: ForgeParser.TupleAccessContext): Type {
            var type = withoutExpected { ctx.receiver.attribType() }
            val path = (ctx.index ?: ctx.path).text.split('.').map { it.toInt() }
            ctx.attach("path", path)
            for (index in path) {
                if (type == ErrorType) return ctx.setType(ErrorType)
                if (type is OptionalType) {
                    chk.checkAssignable(type.elem, type)
                    return ctx.setType(ErrorType)
                }
                val tuple = type as? TupleType
                if (tuple == null) {
                    log.diag(typeMismatch(ctx.receiver.span, TupleType(listOf(ArbitraryType, ArbitraryType)), type))
                    return ctx.setType(ErrorType)
                }
                if (index >= tuple.elems.size) {
                    log.diag(tupleIndexOutOfRange(ctx.span, tuple, index))
                    return ctx.setType(ErrorType)
                }
                type = tuple.elems[index]
            }
            return ctx.setType(type)
        }

        override fun visitTuplePattern(ctx: ForgeParser.TuplePatternContext): Type {
            val expected = env.value.expected!!
            val args = ctx.patternArg()
            val tuple = expected as? TupleType
            if (expected != ErrorType && (tuple == null || tuple.elems.size != args.size)) {
                log.diag(typeMismatch(ctx.span, TupleType(args.map { ArbitraryType }), expected))
            }
            for ((i, arg) in args.withIndex()) {
                val elem = tuple?.elems?.getOrNull(i) ?: ErrorType
                withExpected(elem) { chk.checkAssignable(elem, arg.attribType()) }
            }
            return ctx.setType(if (tuple != null && tuple.elems.size == args.size) tuple else ErrorType)
        }

        // ---- string interpolation ---------------------------------------------------------------

        /** The parts of `"a ${x} b"` that are expressions are put into the text with `str()` (strs as they are). */
        private fun attribInterpolated(part: ForgeParser.ExpressionContext) {
            val type = withoutExpected { part.attribType() }
            if (type == ErrorType || type == StrType) return
            val str = (if (type is OptionalType || type == NullType || type == NothingType) null else resolveMember(type, "str", Kind.VAL, Kind.MTH)) as? FunctionSymbol
            if (str == null || str.params.isNotEmpty() || str.returnType != StrType) {
                log.diag(notInterpolatable(part.span, type))
                return
            }
            part.attach("str", str)
        }

        override fun visitErrorNode(node: ErrorNode): Type {
            log.diag(parseError(node.span, node.text))
            return ErrorType
        }

    }

}
