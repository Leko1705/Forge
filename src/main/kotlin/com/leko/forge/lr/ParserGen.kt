package com.leko.forge.lr

import ForgeParser
import com.leko.forge.analysis.invalidAnnotation
import com.leko.forge.analysis.parserConflict
import com.leko.forge.analysis.ruleWithoutProductions
import com.leko.forge.attr.Resolve
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.labelSymbols
import com.leko.forge.attr.parseInfo
import com.leko.forge.attr.symbol
import com.leko.forge.context.Context
import com.leko.forge.model.ParseInfo
import com.leko.forge.model.ParserRuleSymbol
import com.leko.forge.model.ParserSymbol
import com.leko.forge.model.SourceSpan
import com.leko.forge.model.VarSymbol
import com.leko.forge.model.span
import com.leko.forge.types.Type
import com.leko.forge.util.Log
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A compile time recipe for computing a semantic value out of the values of the
 * right hand side of a production (`rhs[i]`). Terminals produce their text,
 * nonterminals of user rules their result, helper nonterminals *frames*
 * (arrays holding a value followed by the values of the labels inside) or
 * *repetitions* (lists of frames).
 */
sealed interface Sem {
    data class Rhs(val index: Int) : Sem
    data object Null : Sem
    data object EmptyList : Sem
    data class Concat(val parts: List<Sem>) : Sem
    data class FrameField(val index: Int, val field: Int) : Sem
    data class RepField(val index: Int, val field: Int) : Sem
    data class Frame(val fields: List<Sem>) : Sem
    data class RepStart(val frame: Frame) : Sem
    data class RepAppend(val index: Int, val frame: Frame) : Sem
    /** concatenation of values and lists of values (`isList`) into one list */
    data class Flatten(val parts: List<Sem>, val isList: List<Boolean>) : Sem

    fun shift(offset: Int): Sem = if (offset == 0) this else when (this) {
        is Rhs -> Rhs(index + offset)
        Null, EmptyList -> this
        is Concat -> Concat(parts.map { it.shift(offset) })
        is FrameField -> FrameField(index + offset, field)
        is RepField -> RepField(index + offset, field)
        is Frame -> Frame(fields.map { it.shift(offset) })
        is RepStart -> RepStart(frame.shift(offset) as Frame)
        is RepAppend -> RepAppend(index + offset, frame.shift(offset) as Frame)
        is Flatten -> Flatten(parts.map { it.shift(offset) }, isList)
    }
}

/** What happens when a production is reduced. */
sealed interface ProductionAction {
    /** A production of a rule option: binds the labels, then evaluates the producer (or [value]). */
    class User(
        val option: ForgeParser.ParserRuleOptionContext,
        val labels: List<Pair<VarSymbol, Sem>>,
        val value: Sem,
        val type: Type,
    ) : ProductionAction

    /** A production of a helper nonterminal introduced for a group or repetition. */
    class Helper(val value: Sem) : ProductionAction
}

class ParserEntry(val rule: String, val index: Int, val type: Type)

class ParserDefinition(
    val symbol: ParserSymbol,
    val grammar: LRGrammar,
    val tables: LRTables,
    val layout: List<String>,
    /** per production */
    val actions: List<ProductionAction>,
    val entries: List<ParserEntry>,
)

/**
 * The parser generator: lowers the rules of every `parser` into a BNF grammar and
 * builds its LR(1) tables.
 *
 * Lowering of the EBNF constructs:
 * - `x?` and alternatives `(a | b)` are expanded inline, i.e. a rule option becomes
 *   one production per combination of choices. Unlike helper nonterminals this
 *   introduces no artificial conflicts (`a? b | a c` is fine). Should an option
 *   expand into too many productions, the culprit is factored out into a helper.
 * - `x+` becomes a left recursive helper `H → x | H x` (constant stack depth),
 *   `x*` is `(x+)?`.
 */
class ParserGen private constructor(context: Context) {

    companion object {
        fun instance(context: Context): ParserGen =
            context.getServices(ParserGen::class) { ParserGen(context) }

        /** maximum number of productions a single group may expand into before a helper is introduced */
        private const val EXPANSION_LIMIT = 64

        private const val MAX_REPORTED_CONFLICTS = 25

        val DEFAULT_LAYOUT = listOf("\\s+")
    }

    private val symTab = SymTab.instance(context)

    private val resolve = Resolve.instance(context)

    private val log = Log.instance(context)

    fun generate(root: ForgeParser.RootContext): List<ParserDefinition> {
        return symTab.parsers.values.sortedBy { it.span.startLine }.map { generate(root, it) }
    }

    private fun layoutOf(root: ForgeParser.RootContext, parser: ParserSymbol): List<String> {
        for (p in parser.lineage) {
            val def = root.parserDef().firstOrNull { it.name.text == p.name } ?: continue
            val skips = def.annotation().filter { it.name.text == "skip" }
            for (annotation in def.annotation()) {
                if (annotation.name.text != "skip") {
                    log.diag(invalidAnnotation(annotation.span, "unknown parser annotation '@${annotation.name.text}', expected @skip('regex', ...)"))
                }
            }
            if (skips.isEmpty()) continue
            val layout = skips.flatMap { it.annotationArg() }.map { arg ->
                val regex = if (arg.STRING() != null) Pattern.quote(arg.argText()) else arg.argText()
                try {
                    Pattern.compile(regex)
                } catch (e: PatternSyntaxException) {
                    log.diag(invalidAnnotation(arg.span, "invalid layout regex '$regex': ${e.description}"))
                }
                regex
            }
            return layout
        }
        return DEFAULT_LAYOUT
    }

    private fun precedenceOf(option: ForgeParser.ParserRuleOptionContext): Precedence? {
        var result: Precedence? = null
        for (annotation in option.annotation()) {
            val assoc = when (annotation.name.text) {
                "left" -> Assoc.LEFT
                "right" -> Assoc.RIGHT
                "nonassoc" -> Assoc.NONASSOC
                "prec" -> Assoc.NONE
                else -> {
                    log.diag(invalidAnnotation(annotation.span, "unknown rule annotation '@${annotation.name.text}', expected @left(n), @right(n), @nonassoc(n) or @prec(n)"))
                    continue
                }
            }
            val args = annotation.annotationArg()
            val level = args.singleOrNull()?.INT()?.text?.toIntOrNull()
            if (level == null) {
                log.diag(invalidAnnotation(annotation.span, "@${annotation.name.text} expects the precedence level as its only argument, e.g. @${annotation.name.text}(10)"))
                continue
            }
            if (result != null) {
                log.diag(invalidAnnotation(annotation.span, "a rule option can only have one precedence"))
            }
            result = Precedence(level, assoc)
        }
        return result
    }

    private class Rule(val name: String, val type: Type, val span: SourceSpan) {
        val options = mutableListOf<ForgeParser.ParserRuleOptionContext>()
    }

    private data class Path(val syms: List<Int>, val value: Sem, val labels: Map<String, Sem>)

    /** Lowers the rules of one parser into [grammar]. */
    private inner class Lowering(private val rules: Map<String, Rule>) {
        val grammar = LRGrammar()
        val actions = mutableListOf<ProductionAction>()
        private val nonterminals = rules.keys.associateWith { grammar.nonterminal(it) }
        val referenced = linkedMapOf<String, SourceSpan>()
        private var helperCount = 0
        private var currentRule = ""

        fun lower() {
            for (rule in rules.values) {
                currentRule = rule.name
                val lhs = nonterminals.getValue(rule.name)
                for (option in rule.options) {
                    val precedence = precedenceOf(option)
                    for (path in expand(option.parseRule())) {
                        val labels = option.labelSymbols.map { it to (path.labels[it.name] ?: Sem.Null) }
                        addProduction(
                            lhs, path.syms,
                            ProductionAction.User(option, labels, path.value, rule.type),
                            precedence, option.span,
                        )
                    }
                }
            }
        }

        fun nonterminal(name: String): Int = nonterminals.getValue(name)

        private fun helper(): Int = grammar.nonterminal("$currentRule@${++helperCount}")

        private fun addProduction(lhs: Int, syms: List<Int>, action: ProductionAction, precedence: Precedence? = null, span: SourceSpan? = null) {
            grammar.production(lhs, syms, precedence, span)
            actions += action
        }

        private fun frameOf(path: Path, labels: Collection<String>): Sem.Frame =
            Sem.Frame(listOf(path.value) + labels.map { path.labels[it] ?: Sem.Null })

        /** Factors [paths] out into a helper nonterminal whose value is a frame. */
        private fun factor(paths: List<Path>, labels: Collection<String>): List<Path> {
            val h = helper()
            for (p in paths) addProduction(h, p.syms, ProductionAction.Helper(frameOf(p, labels)))
            return listOf(Path(listOf(LRGrammar.nt(h)), Sem.FrameField(0, 0), labels.withIndex().associate { (k, l) -> l to Sem.FrameField(0, k + 1) }))
        }

        private fun guard(paths: List<Path>, info: ParseInfo): List<Path> =
            if (paths.size <= EXPANSION_LIMIT) paths else factor(paths, info.labels.keys)

        private fun expand(ctx: ForgeParser.ParseRuleContext): List<Path> = expandAlternative(ctx.parserAlternative())

        private fun expandAlternative(ctx: ForgeParser.ParserAlternativeContext): List<Path> {
            val labels = ctx.parseInfo.labels.keys
            val paths = ctx.parserSequence().flatMap { seq ->
                expandSequence(seq).map { p -> p.copy(labels = labels.associateWith { p.labels[it] ?: Sem.Null }) }
            }
            return guard(paths, ctx.parseInfo)
        }

        private fun expandSequence(ctx: ForgeParser.ParserSequenceContext): List<Path> {
            val elems = ctx.parserLabeled()
            val expansions = elems.map { expandLabeled(it) }.toMutableList()
            // keep the number of combinations bounded by factoring out the biggest elements
            while (expansions.fold(1L) { acc, e -> acc * e.size } > EXPANSION_LIMIT) {
                val i = expansions.indices.maxBy { expansions[it].size }
                expansions[i] = factor(expansions[i], elems[i].parseInfo.labels.keys)
            }
            var partial = listOf(Path(emptyList(), Sem.Null, emptyMap()) to emptyList<Sem>())
            for (expansion in expansions) {
                partial = partial.flatMap { (a, values) ->
                    val offset = a.syms.size
                    expansion.map { b ->
                        Path(a.syms + b.syms, Sem.Null, a.labels + b.labels.mapValues { it.value.shift(offset) }) to
                                (values + b.value.shift(offset))
                    }
                }
            }
            val valueIndices = elems.indices.filter { elems[it].parseInfo.kind == ParseInfo.Kind.VALUE }
            return partial.map { (path, values) ->
                val value = when {
                    elems.isEmpty() -> Sem.Null
                    elems.size == 1 -> values[0]
                    valueIndices.size == 1 -> values[valueIndices[0]]
                    valueIndices.isEmpty() -> Sem.Concat(values)
                    ctx.parseInfo.flatten -> Sem.Flatten(
                        valueIndices.map { values[it] },
                        valueIndices.map { elems[it].parseInfo.type is com.leko.forge.types.ListType },
                    )
                    else -> Sem.Null
                }
                path.copy(value = value)
            }
        }

        private fun expandLabeled(ctx: ForgeParser.ParserLabeledContext): List<Path> {
            val inner = expandPostfix(ctx.parserPostfix())
            val label = ctx.label?.text ?: return inner
            return inner.map { it.copy(labels = it.labels + (label to it.value)) }
        }

        private fun expandPostfix(ctx: ForgeParser.ParserPostfixContext): List<Path> {
            val inner = expandAtom(ctx.atom)
            val labels = ctx.atom.parseInfo.labels.keys
            if (ctx.QUESTIONMARK() != null) {
                return guard(inner + Path(emptyList(), Sem.Null, labels.associateWith { Sem.Null }), ctx.parseInfo)
            }
            if (ctx.PLUS() == null && ctx.STAR() == null) return inner
            val h = helper()
            for (p in inner) {
                val frame = frameOf(p, labels)
                addProduction(h, p.syms, ProductionAction.Helper(Sem.RepStart(frame)))
                addProduction(h, listOf(LRGrammar.nt(h)) + p.syms, ProductionAction.Helper(Sem.RepAppend(0, frame.shift(1) as Sem.Frame)))
            }
            val plus = Path(listOf(LRGrammar.nt(h)), Sem.RepField(0, 0), labels.withIndex().associate { (k, l) -> l to Sem.RepField(0, k + 1) })
            return if (ctx.STAR() != null) {
                listOf(plus, Path(emptyList(), Sem.EmptyList, labels.associateWith { Sem.EmptyList }))
            } else {
                listOf(plus)
            }
        }

        private fun expandAtom(ctx: ForgeParser.ParserAtomContext): List<Path> = when {
            ctx.terminalRule() != null -> {
                val syntax = ctx.terminalRule().terminalSyntax()
                listOf(Path(listOf(grammar.terminal(syntax.pattern, syntax.literal)), Sem.Rhs(0), emptyMap()))
            }
            ctx.nonterminalRule() != null -> {
                val name = ctx.nonterminalRule().name.text
                // unknown rules have already been reported by Attr; they become nonterminals without productions
                val nt = nonterminals[name] ?: grammar.nonterminal(name)
                referenced.putIfAbsent(name, ctx.span)
                listOf(Path(listOf(LRGrammar.nt(nt)), Sem.Rhs(0), emptyMap()))
            }
            else -> expand(ctx.parseRule())
        }
    }

    private fun generate(root: ForgeParser.RootContext, parser: ParserSymbol): ParserDefinition {
        // collect the rules, inherited ones first
        val rules = linkedMapOf<String, Rule>()
        for (p in parser.lineage.reversed()) {
            for (def in root.parseRuleDef()) {
                if (def.parserName.text != p.name) continue
                val name = def.ruleName.text
                val sym = resolve.resolveParserRule(parser, name) as? ParserRuleSymbol ?: continue
                val rule = rules.getOrPut(name) { Rule(name, sym.returnType, def.ruleName.span) }
                if (def.ARROW() != null && def.symbol != null) rule.options += def.parserRuleOption()
            }
        }

        val lowering = Lowering(rules)
        lowering.lower()
        for ((name, span) in lowering.referenced) {
            if (rules[name]?.options.isNullOrEmpty()) log.diag(ruleWithoutProductions(span, name))
        }

        val grammar = lowering.grammar
        val entries = rules.values.filter { it.options.isNotEmpty() }.mapIndexed { i, rule ->
            grammar.entries += lowering.nonterminal(rule.name)
            ParserEntry(rule.name, i, rule.type)
        }

        val tables = LRBuilder(grammar).build()
        reportConflicts(parser, tables.conflicts)

        return ParserDefinition(parser, grammar, tables, layoutOf(root, parser), lowering.actions, entries)
    }

    private fun reportConflicts(parser: ParserSymbol, conflicts: List<Conflict>) {
        val unique = conflicts.distinctBy { it.terminal to it.items }
        for (conflict in unique.take(MAX_REPORTED_CONFLICTS)) {
            log.diag(parserConflict(conflict.productions.firstNotNullOfOrNull { it.span } ?: parser.span, parser.name, conflict.toString()))
        }
        if (unique.size > MAX_REPORTED_CONFLICTS) {
            log.diag(parserConflict(parser.span, parser.name, "${unique.size - MAX_REPORTED_CONFLICTS} more conflicts"))
        }
    }
}
