package com.leko.forge.analysis

import ForgeParser
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TreeAttributes
import com.leko.forge.attr.typeOrNull
import com.leko.forge.context.Context
import com.leko.forge.model.EnumItemSymbol
import com.leko.forge.model.EnumSymbol
import com.leko.forge.model.span
import com.leko.forge.types.*
import com.leko.forge.util.Log
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree

/**
 * The cases of `match`: each must be reachable (the cases before it do not match all its values), and together with
 * `_ ->` they must match every value of the matched type. Both are checked as in Rust and OCaml, with the
 * "usefulness" of a row of patterns over the rows before it (Maranget, *Warnings for pattern matching*): patterns
 * are constructors with arguments, enum items, tuples, `[]` and `head :: tail` (`[a, b]` is `a :: b :: []`), `true`
 * and `false`, `null`; other values (numbers, strings, constants) are one constructor each of a type that has
 * infinitely many, which only `_` and variables cover.
 *
 * A case with a guard covers nothing, and nor does one that compares an enum, a list, a tuple, a bool or an
 * optional with a value (`Type.INT` written as a constant): what such a value is is not known before the program
 * runs. Both can be unreachable themselves.
 */
class MatchCoverage private constructor(context: Context) {
    companion object {
        fun instance(context: Context): MatchCoverage =
            context.getServices(MatchCoverage::class) { MatchCoverage(context) }
    }

    private val log = Log.instance(context)
    private val symTab = SymTab.instance(context)

    // ---- patterns as constructors ----------------------------------------------------------------------------

    private sealed interface Ctor { val arity: Int }
    private data class Item(val enum: String, val name: String, override val arity: Int) : Ctor
    private data class Tuple(override val arity: Int) : Ctor
    private data object Nil : Ctor { override val arity = 0 }
    private data object Cons : Ctor { override val arity = 2 }
    private data class Bool(val value: Boolean) : Ctor { override val arity = 0 }
    private data object Null : Ctor { override val arity = 0 }
    /** A value of a type with infinitely many: equal patterns are the same value. */
    private data class Literal(val text: String) : Ctor { override val arity = 0 }

    private sealed interface Pat
    private data object Wild : Pat
    private data class Con(val ctor: Ctor, val args: List<Pat>) : Pat

    /** A pattern that can not be checked: of a type with errors, or a value where constructors are. */
    private class Unknown : Exception()

    fun check(root: ParseTree) {
        if (root is ForgeParser.MatchContext) check(root)
        for (i in 0 until root.childCount) check(root.getChild(i))
    }

    private fun check(match: ForgeParser.MatchContext) {
        val type = match.switch_?.typeOrNull ?: return
        if (unknown(type)) return
        val cases = match.matchCase()
        // `match x {}` is an error of its own
        if (cases.isEmpty() && match.alt == null) return
        // the rows that cover what they match: no guard, every pattern known
        val covering = mutableListOf<List<Pat>>()
        for (case in cases) {
            val pattern = try { pattern(case.pattern(), type) } catch (_: Unknown) { null }
            if (pattern != null && !useful(covering, listOf(pattern), listOf(type))) {
                log.diag(unreachableMatchCase(case.pattern().span))
                continue
            }
            if (pattern != null && case.cond == null) covering += listOf(pattern)
        }
        if (match.alt != null) {
            if (!useful(covering, listOf(Wild), listOf(type))) log.diag(unreachableMatchCase(match.UNDERSCORE().span))
            return
        }
        val missing = missing(covering, listOf(type)) ?: return
        log.diag(matchNotExhaustive(match.MATCH().span, show(missing.single(), type)))
    }

    private fun unknown(type: Type): Boolean = when (type) {
        ErrorType, ArbitraryType -> true
        is OptionalType -> unknown(type.elem)
        is ListType -> unknown(type.elem)
        is TupleType -> type.elems.any(::unknown)
        is ComplexType -> type.args.any(::unknown)
        else -> false
    }

    private fun pattern(tree: ParserRuleContext, type: Type): Pat = when (tree) {
        is ForgeParser.PatternContext -> pattern(tree.getChild(0) as ParserRuleContext, type)
        is ForgeParser.WildcardPatternContext, is ForgeParser.PatternVariableContext -> Wild
        is ForgeParser.TypePatternContext -> {
            val item = TreeAttributes.attributes[tree]?.get("symbol") as? EnumItemSymbol ?: throw Unknown()
            val args = tree.patternArg()
            if (args.size != item.parameters.size) throw Unknown()
            val types = argTypes(Item(item.enumType, item.name, args.size), type)
            Con(Item(item.enumType, item.name, args.size), args.zip(types).map { (a, t) -> pattern(a, t) })
        }
        is ForgeParser.TuplePatternContext -> {
            val types = (type as? TupleType)?.elems ?: throw Unknown()
            if (types.size != tree.patternArg().size) throw Unknown()
            Con(Tuple(types.size), tree.patternArg().zip(types).map { (a, t) -> pattern(a, t) })
        }
        is ForgeParser.ListPatternContext -> {
            val elem = (type.nonNull() as? ListType)?.elem ?: throw Unknown()
            tree.patternArg().foldRight(Con(Nil, emptyList()) as Pat) { a, rest -> Con(Cons, listOf(pattern(a, elem), rest)) }
        }
        is ForgeParser.SplitPatternContext -> split(tree.element, tree.rest, type)
        is ForgeParser.SplitPatternArgContext -> split(tree.element, tree.rest, type)
        is ForgeParser.ExprPatternContext -> value(tree.expression(), type)
        is ForgeParser.ExpressionArgContext -> value(tree.exprPattern().expression(), type)
        // the labeled pattern arguments wrap one pattern
        else -> if (tree.childCount == 1 && tree.getChild(0) is ParserRuleContext) pattern(tree.getChild(0) as ParserRuleContext, type) else throw Unknown()
    }

    private fun split(head: ParserRuleContext, rest: ParserRuleContext, type: Type): Pat {
        val list = type.nonNull() as? ListType ?: throw Unknown()
        return Con(Cons, listOf(pattern(head, list.elem), pattern(rest, list)))
    }

    /** A value as a pattern: `null`, `true`, `false`, or one of a type with infinitely many. */
    private fun value(expression: ForgeParser.ExpressionContext, type: Type): Pat {
        if (expression is ForgeParser.NullValueContext) return Con(Null, emptyList())
        if (expression is ForgeParser.BoolValueContext) return Con(Bool(expression.TRUE() != null), emptyList())
        // a value where the type has constructors: which one it is is not known
        if (signature(type) != null) throw Unknown()
        return Con(Literal(expression.text), emptyList())
    }

    // ---- the constructors of types ---------------------------------------------------------------------------

    private fun Type.nonNull(): Type = if (this is OptionalType) elem else this

    private fun enumOf(type: Type): EnumSymbol? = (type as? ComplexType)?.let { t -> symTab.enums.values.find { it.name == t.name } }

    /** All the constructors of [type], or `null` if it has infinitely many. */
    private fun signature(type: Type): List<Ctor>? = when (type) {
        is OptionalType -> signature(type.elem)?.plus(Null)
        BoolType -> listOf(Bool(false), Bool(true))
        is TupleType -> listOf(Tuple(type.elems.size))
        is ListType -> listOf(Nil, Cons)
        is ComplexType -> enumOf(type)?.items?.map { Item(it.enumType, it.name, it.parameters.size) }
        else -> null
    }

    /** The types of the arguments of [ctor] in a value of [type]. */
    private fun argTypes(ctor: Ctor, type: Type): List<Type> {
        val t = type.nonNull()
        return when (ctor) {
            is Item -> {
                val enum = enumOf(t)
                val item = enum?.items?.find { it.name == ctor.name } ?: return List(ctor.arity) { ArbitraryType }
                val bindings = if (t is ComplexType && t.args.size == enum.typeParams.size) enum.typeParams.zip(t.args).toMap() else emptyMap()
                item.parameters.map { it.second.substitute(bindings) }
            }
            is Tuple -> (t as? TupleType)?.elems ?: List(ctor.arity) { ArbitraryType }
            Cons -> listOf((t as? ListType)?.elem ?: ArbitraryType, t)
            else -> emptyList()
        }
    }

    // ---- usefulness ------------------------------------------------------------------------------------------

    private fun wilds(n: Int): List<Pat> = List(n) { Wild }

    /** The rows that match [ctor] first, with its arguments in place of it. */
    private fun specialize(rows: List<List<Pat>>, ctor: Ctor): List<List<Pat>> = rows.mapNotNull { row ->
        when (val head = row.first()) {
            Wild -> wilds(ctor.arity) + row.drop(1)
            is Con -> if (head.ctor == ctor) head.args + row.drop(1) else null
        }
    }

    /** The rows that match anything first, without it. */
    private fun default(rows: List<List<Pat>>): List<List<Pat>> = rows.filter { it.first() == Wild }.map { it.drop(1) }

    private fun heads(rows: List<List<Pat>>): Set<Ctor> = rows.mapNotNull { (it.first() as? Con)?.ctor }.toSet()

    /** Whether some value matches [row] but none of [rows]; [types] are those of the columns. */
    private fun useful(rows: List<List<Pat>>, row: List<Pat>, types: List<Type>): Boolean {
        if (row.isEmpty()) return rows.isEmpty()
        val type = types.first()
        return when (val head = row.first()) {
            is Con -> useful(specialize(rows, head.ctor), head.args + row.drop(1), argTypes(head.ctor, type) + types.drop(1))
            Wild -> {
                val all = signature(type)
                val used = heads(rows)
                if (all != null && used.containsAll(all)) {
                    all.any { c -> useful(specialize(rows, c), wilds(c.arity) + row.drop(1), argTypes(c, type) + types.drop(1)) }
                } else {
                    useful(default(rows), row.drop(1), types.drop(1))
                }
            }
        }
    }

    /** Values that no row of [rows] matches, one pattern per column; `null` if they match every value. */
    private fun missing(rows: List<List<Pat>>, types: List<Type>): List<Pat>? {
        if (types.isEmpty()) return if (rows.isEmpty()) emptyList() else null
        val type = types.first()
        val all = signature(type)
        val used = heads(rows)
        if (all != null && used.containsAll(all)) {
            for (c in all) {
                val w = missing(specialize(rows, c), argTypes(c, type) + types.drop(1)) ?: continue
                return listOf(Con(c, w.take(c.arity))) + w.drop(c.arity)
            }
            return null
        }
        val rest = missing(default(rows), types.drop(1)) ?: return null
        // a constructor that no row starts with, if the type has few enough to name one
        val head = all?.firstOrNull { it !in used }?.let { Con(it, wilds(it.arity)) } ?: Wild
        return listOf(head) + rest
    }

    // ---- how a missing value is written ----------------------------------------------------------------------

    private fun show(pat: Pat, type: Type): String = when (pat) {
        Wild -> "_"
        is Con -> when (val c = pat.ctor) {
            is Item -> displayName(c.enum) + "." + c.name +
                if (c.arity == 0) "" else pat.args.zip(argTypes(c, type)).joinToString(", ", "(", ")") { (p, t) -> show(p, t) }
            is Tuple -> pat.args.zip(argTypes(c, type)).joinToString(", ", "(", ")") { (p, t) -> show(p, t) }
            Nil -> "[]"
            Cons -> {
                // [a, b] if the list ends, else a :: rest
                val elems = mutableListOf<Pat>()
                var p: Pat = pat
                while (p is Con && p.ctor == Cons) { elems += p.args[0]; p = p.args[1] }
                val elem = (type.nonNull() as? ListType)?.elem ?: ArbitraryType
                if (p is Con && p.ctor == Nil) elems.joinToString(", ", "[", "]") { show(it, elem) }
                else (elems.map { show(it, elem) } + show(p, type)).joinToString(" :: ")
            }
            is Bool -> c.value.toString()
            Null -> "null"
            is Literal -> c.text
        }
    }
}
