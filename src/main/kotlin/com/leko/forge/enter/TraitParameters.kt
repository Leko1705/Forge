package com.leko.forge.enter

import ForgeParser
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNodeImpl

/** The prefix of the type parameters [desugarTraitParameters] introduces: `__impl0_Printable`. */
const val TRAIT_PARAMETER_PREFIX = "__impl"

/** The trait a type parameter of [desugarTraitParameters] stands for (`Printable` for `__impl0_Printable`), or `null`. */
fun traitOfParameter(typeParam: String): String? =
    if (typeParam.startsWith(TRAIT_PARAMETER_PREFIX) && '_' in typeParam.removePrefix(TRAIT_PARAMETER_PREFIX))
        typeParam.removePrefix(TRAIT_PARAMETER_PREFIX).substringAfter('_')
    else null

/**
 * A trait as the type of a parameter is a type parameter with the trait as its bound:
 * `fun show(x: Printable): str` is `fun <T: Printable> show(x: T): str`. Each such use is a type parameter of its
 * own, also inside other types (`xs: List<Printable>` is `xs: List<T>`: a list of one type that is Printable).
 *
 * Runs after [Modules] (trait names are qualified) and before the declarations are entered: it rewrites the
 * parameter types of functions in [root], so every later phase sees the generic function.
 */
fun desugarTraitParameters(root: ForgeParser.RootContext) {
    val traits = root.traitDef().map { it.name.text }.toSet()
    if (traits.isEmpty()) return
    for (func in root.function()) {
        val declared = func.typeParams().names().toSet()
        val added = mutableListOf<Pair<String, ForgeParser.NamedTypeContext>>()
        fun rewrite(type: ForgeParser.TypeContext?) {
            when (type) {
                null -> {}
                is ForgeParser.NamedTypeContext -> {
                    val name = type.name.text
                    if (name in traits && name !in declared && type.args.isEmpty()) {
                        val fresh = "$TRAIT_PARAMETER_PREFIX${added.size}_$name"
                        // the bound keeps the trait as it was written (its token for the editors)
                        val bound = ForgeParser.NamedTypeContext(ForgeParser.TypeContext(null, -1))
                        bound.name = CommonToken(type.name).also { it.text = name }
                        bound.start = type.start
                        bound.stop = type.stop
                        added += fresh to bound
                        (type.name as CommonToken).text = fresh
                    } else {
                        type.args.forEach { rewrite(it) }
                    }
                }
                else -> type.children?.filterIsInstance<ForgeParser.TypeContext>()?.forEach { rewrite(it) }
            }
        }
        func.parameter().forEach { rewrite(it.ty) }
        if (added.isEmpty()) continue

        val params = func.typeParams() ?: ForgeParser.TypeParamsContext(func, -1).also { addBefore(func, it) }
        for ((name, bound) in added) {
            val param = ForgeParser.TypeParamContext(params, -1)
            param.name = CommonToken(bound.name).also { it.text = name }
            bound.parent = param
            param.bounds.add(bound)
            param.addAnyChild(bound)
            param.start = bound.start
            param.stop = bound.stop
            params.addAnyChild(param)
        }
        if (params.start == null) {
            params.start = added.first().second.start
            params.stop = added.last().second.stop
        }
    }
}

/** Makes [child] a child of [func], where a written `<...>` would be: after `fun`. */
private fun addBefore(func: ForgeParser.FunctionContext, child: ParserRuleContext) {
    val children = func.children
    val index = children.indexOfFirst { it is ForgeParser.TypeContext || it is TerminalNodeImpl && (it.symbol.type == ForgeParser.IDENT && it.symbol === func.name) }
    children.add(if (index < 0) children.size else index, child)
    child.parent = func
}
