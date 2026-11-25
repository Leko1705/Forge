package com.leko.forge.ide.semantics

import ForgeParser
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.model.span
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree

/**
 * What the paths of `use` declarations name: `use geometry` the module `geometry`, `use geometry::Point` the
 * declaration `Point` in it. The compiler has no symbols for modules (it makes their declarations declarations of
 * the root), so this is computed from the tree of the whole program before the analysis changes it.
 *
 * A path is looked up as the compiler looks it up: in the module the `use` is in, then in the modules around it.
 */
internal fun moduleLinks(root: ForgeParser.RootContext): Map<Token, ForgeResolution> {
    // qualified module names (`geometry::shapes`) to their names, and to the names of their declarations
    val modules = mutableMapOf<String, Token>()
    val declarations = mutableMapOf<String, MutableMap<String, Token>>()
    val uses = mutableListOf<Pair<ForgeParser.UseDeclContext, List<String>>>()

    fun declared(t: ParseTree): Token? = when (t) {
        is ForgeParser.NodeDefContext -> t.name
        is ForgeParser.RecordContext -> t.name
        is ForgeParser.EnumContext -> t.name
        is ForgeParser.FunctionContext -> t.name.takeIf { t.receiver == null && t.property == null }
        is ForgeParser.ConstDefContext -> t.name
        is ForgeParser.ParserDefContext -> t.name
        is ForgeParser.GraphDefContext -> t.name
        is ForgeParser.TraitDefContext -> t.name
        is ForgeParser.ModDefContext -> t.name
        else -> null
    }

    fun walk(t: ParseTree, path: List<String>) {
        for (i in 0 until t.childCount) {
            val child = t.getChild(i)
            if (path.isNotEmpty()) declared(child)?.let { declarations.getOrPut(path.joinToString("::")) { mutableMapOf() }[it.text] = it }
            when (child) {
                is ForgeParser.ModDefContext -> {
                    val inner = path + child.name.text
                    modules[inner.joinToString("::")] = child.name
                    walk(child, inner)
                }
                is ForgeParser.UseDeclContext -> uses += child to path
            }
        }
    }
    walk(root, emptyList())

    val links = mutableMapOf<Token, ForgeResolution>()
    for ((use, path) in uses) {
        val written = use.path ?: continue
        val target = (path.size downTo 0).firstNotNullOfOrNull { k ->
            val full = (path.take(k) + written.text).joinToString("::")
            modules[full] ?: full.substringBeforeLast("::", "").takeIf { it.isNotEmpty() }?.let { owner ->
                declarations[owner]?.get(full.substringAfterLast("::"))
            }
        } ?: continue
        val span = target.span
        links[written] = ForgeResolution(target.text, span.source, span.startLine, span.startCol,
            if (modules.containsValue(target)) ForgeNameRole.MODULE else null)
    }
    return links
}
