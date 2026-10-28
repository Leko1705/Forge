package com.leko.forge.lr

import ForgeParser
import com.leko.forge.utils.literalOfRegex
import com.leko.forge.utils.unescapeStringLiteral

/** A terminal as written in a parser rule: `"text"` is literal, `'regex'` is a regular expression. */
data class TerminalSyntax(val pattern: String, val literal: Boolean) {
    /** the regular expression this terminal is matched with */
    val regex: String get() = if (literal) java.util.regex.Pattern.quote(pattern) else pattern
}

fun ForgeParser.TerminalRuleContext.terminalSyntax(): TerminalSyntax {
    STRING()?.let { return TerminalSyntax(unescapeStringLiteral(it.text), true) }
    val text = REGEX_STRING().text
    val regex = text.substring(1, text.length - 1)
    // a regex without meta characters is a literal; this makes '\+' and "+" the same terminal
    val literal = literalOfRegex(regex)
    return if (literal != null) TerminalSyntax(literal, true) else TerminalSyntax(regex, false)
}

/** Text of an annotation argument: strings and regexes without their quotes. */
fun ForgeParser.AnnotationArgContext.argText(): String = when {
    STRING() != null -> unescapeStringLiteral(STRING().text)
    REGEX_STRING() != null -> REGEX_STRING().text.let { it.substring(1, it.length - 1) }
    else -> text
}
