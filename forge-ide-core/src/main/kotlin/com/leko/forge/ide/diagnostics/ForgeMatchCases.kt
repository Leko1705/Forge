package com.leko.forge.ide.diagnostics

import ForgeParser
import com.leko.forge.attr.Resolve
import com.leko.forge.attr.symbol
import com.leko.forge.attr.typeOrNull
import com.leko.forge.context.Context
import com.leko.forge.model.EnumItemSymbol
import com.leko.forge.model.EnumSymbol
import com.leko.forge.types.ComplexType
import com.leko.forge.types.OptionalType
import com.leko.forge.types.displayName
import org.antlr.v4.runtime.tree.ParseTree

/**
 * `Add remaining cases` for a `match` of an enum that does not have a case for each of its items: a case
 * `Type.ERROR(msg) -> TODO("not yet implemented")` for each one missing, its fields bound to variables of their names.
 * An item counts as matched by a case without a guard whose fields are all bound or `_`; the cases are added after
 * the last one, before a `_ ->`. The fix is offered on `match ... {`. Computed while the attributes of the analysis
 * are there.
 */
class ForgeMatchCases(context: Context, private val text: String) {

    private val resolve = Resolve.instance(context)

    /** An [ForgeSeverity.ASSIST] on `match ... {` for each match that misses items, with the fix. */
    fun assists(main: ForgeParser.RootContext): List<ForgeProblem> = buildList {
        fun walk(t: ParseTree) {
            if (t is ForgeParser.MatchContext) fix(t)?.let { fix ->
                val range = ForgeTextRange(t.MATCH().symbol.startIndex, t.LBRACE().symbol.stopIndex + 1)
                add(ForgeProblem(ForgeSeverity.ASSIST, fix.title, range, fix.title, listOf(fix)))
            }
            for (i in 0 until t.childCount) walk(t.getChild(i))
        }
        walk(main)
    }

    private fun enumOf(match: ForgeParser.MatchContext): EnumSymbol? {
        val type = match.switch_?.typeOrNull?.let { if (it is OptionalType) it.elem else it } ?: return null
        return (resolve.resolve(type) ?: (type as? ComplexType)?.let { resolve.resolve(it.name) }) as? EnumSymbol
    }

    private fun fix(match: ForgeParser.MatchContext): ForgeFix? {
        val enum = enumOf(match) ?: return null
        val lbrace = match.LBRACE()?.symbol ?: return null
        val rbrace = match.RBRACE()?.symbol?.takeIf { it.startIndex >= 0 } ?: return null
        val cases = match.matchCase()
        val matched = cases.filter { it.cond == null }.mapNotNull { case ->
            val pattern = case.pattern().typePattern() ?: return@mapNotNull null
            val all = pattern.patternArg().all { it is ForgeParser.WildcardPatternArgContext || it is ForgeParser.PatternVariableArgContext }
            (pattern.symbol as? EnumItemSymbol)?.takeIf { all }?.name
        }.toSet()
        val missing = enum.items.filter { it.name !in matched }
        if (missing.isEmpty()) return null

        // the enum as the cases there name it, else by its name
        val receiver = cases.firstNotNullOfOrNull { it.pattern().typePattern()?.receiver?.text } ?: displayName(enum.name)
        val body = "TODO(\"not yet implemented\")"
        val missingCases = missing.map { item ->
            val fields = if (item.parameters.isEmpty()) "" else item.parameters.joinToString(", ", "(", ")") { it.first }
            "$receiver.${item.name}$fields -> $body"
        }
        val alt = match.UNDERSCORE()?.symbol
        val first = cases.firstOrNull()?.start ?: alt
        val title = "Add remaining cases"
        val name = match.MATCH().symbol.startIndex
        val head = lbrace.stopIndex + 1

        if (first == null) {
            // no case: one per line, between the braces
            val base = indentOf(lbrace.startIndex)
            val text = missingCases.joinToString("") { "\n$base    $it" } + "\n$base"
            return ForgeFix(title, head, text, name, replace = rbrace.startIndex - head)
        }
        val multiLine = line(first.startIndex) != line(lbrace.startIndex)
        val indent = if (multiLine) indentOf(first.startIndex) else ""
        return if (cases.isNotEmpty()) {
            // after the last case
            val end = cases.last().stop.stopIndex + 1
            val text = missingCases.joinToString("") { if (multiLine) "\n$indent$it" else "  $it" }
            ForgeFix(title, end, text, name)
        } else {
            // only `_ -> ...`: before it
            val text = missingCases.joinToString("") { if (multiLine) "$it\n$indent" else "$it  " }
            ForgeFix(title, alt!!.startIndex, text, name)
        }
    }

    private fun line(offset: Int): Int = text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' }

    /** The spaces and tabs at the start of the line of [offset]. */
    private fun indentOf(offset: Int): String {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (offset == 0 || it < 0) 0 else it + 1 }
        return text.substring(lineStart, offset.coerceAtLeast(lineStart)).takeWhile { it == ' ' || it == '\t' }
    }
}
