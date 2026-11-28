package com.leko.forge.analysis

import ForgeParser
import com.leko.forge.attr.typeOrNull
import com.leko.forge.context.Context
import com.leko.forge.model.span
import com.leko.forge.types.*
import com.leko.forge.util.Log
import org.antlr.v4.runtime.tree.ParseTree

/**
 * Declarations of an optional type (`T?`) whose values are never null: a function or `val`, a constant, a `syn`,
 * `inh`, `circular` or `coll` attribute with all its equations (`inh function: Function? = fail(...)` with
 * `inh Function.function { body <- this }`). The `?` is a warning; the type could be `T`, which callers then need not
 * check. Values that only fail (`fail(...)`, `TODO(...)`) say nothing; a declaration with no other value is left as
 * it is, and so is one whose values have type parameters, which may stand for optional types.
 */
class Nullability private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Nullability =
            context.getServices(Nullability::class) { Nullability(context) }
    }

    private val log = Log.instance(context)

    fun check(root: ForgeParser.RootContext) {
        val all = mutableListOf<ParseTree>()
        fun collect(t: ParseTree) {
            all += t
            if (t is ForgeParser.RootContext || t is ForgeParser.ModDefContext) for (i in 0 until t.childCount) collect(t.getChild(i))
        }
        collect(root)

        for (f in all.filterIsInstance<ForgeParser.FunctionContext>()) {
            val body = f.expression() ?: continue
            report(f.ty, listOf(body), (if (f.property != null) "val '" else "function '") + f.name.text + "'")
        }
        for (c in all.filterIsInstance<ForgeParser.ConstDefContext>()) c.ty?.let { report(it, listOf(c.value), "constant '${c.name.text}'") }

        // an attribute: its definition and the equations of the nodes
        val syns = all.filterIsInstance<ForgeParser.SynDefContext>().groupBy { it.attrName.text }
        val circulars = all.filterIsInstance<ForgeParser.CircularDefContext>().groupBy { it.attrName.text }
        for ((name, defs) in syns) {
            val values = defs.mapNotNull { it.syn } + circulars[name].orEmpty().flatMap { listOf(it.init, it.body) }
            for (def in defs) report(def.attrType, values, "attribute '$name'")
        }
        for ((name, defs) in circulars) {
            if (name in syns) continue
            val values = defs.flatMap { listOf(it.init, it.body) }
            for (def in defs) report(def.attrType, values, "attribute '$name'")
        }
        val inhEquations = all.filterIsInstance<ForgeParser.InhImplContext>().groupBy { it.attrName.text }
        for (inh in all.filterIsInstance<ForgeParser.InhDefContext>()) {
            val name = inh.attrName.text
            report(inh.attrType, listOf(inh.init) + inhEquations[name].orEmpty().flatMap { it.inhDelegate() }.map { it.value }, "attribute '$name'")
        }
        val contribs = all.filterIsInstance<ForgeParser.ContribContext>().groupBy { it.collName.text }
        for (coll in all.filterIsInstance<ForgeParser.CollContext>()) {
            val name = coll.collName.text
            report(coll.collType, listOf(coll.init) + contribs[name].orEmpty().map { it.value }, "attribute '$name'")
        }
    }

    /** The warning on the `?` of [declared] if none of [values] may be null. */
    private fun report(declared: ForgeParser.TypeContext?, values: List<ForgeParser.ExpressionContext>, what: String) {
        if (declared !is ForgeParser.OptionalTypeContext) return
        val types = values.map { it.typeOrNull ?: return }
        // values that fail say nothing; without others nothing is known
        val known = types.filter { it != NothingType }
        if (known.isEmpty()) return
        if (known.any { it is OptionalType || it == NullType || it == ErrorType || it == ArbitraryType || it.hasTypeVars() }) return
        log.diag(unnecessaryOptionalType(declared.QUESTIONMARK().span, what, declared.ty.text))
    }
}
