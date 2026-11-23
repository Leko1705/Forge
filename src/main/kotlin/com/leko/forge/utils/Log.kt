package com.leko.forge.util

import com.leko.forge.context.Context
import com.leko.forge.model.SourceSpan

private const val ANSI_RESET = "\u001B[0m"
private const val ANSI_RED = "\u001B[91m"
private const val ANSI_WHITE = "\u001B[37m"

private const val ANSI_YELLOW = "\u001B[33m"

enum class Severity { ERROR, WARNING, INFO }

data class Diagnostic(
    val severity: Severity,
    val span: SourceSpan?,
    val code: String,
    val message: String
)

/**
 * Shared diagnostics sink (javac's Log analog). Every phase reports into the same
 * instance so errors from Enter, completeness checking, circularity checking, etc.
 * all accumulate together instead of the caller having to stitch per-phase results
 * back together. Collect-then-report, not throw-on-first — see the earlier design
 * discussion on error handling.
 */
class Log private constructor() {
    companion object {
        fun instance(context: Context): Log =
            context.getServices(Log::class) { Log() }
    }

    val diagnostics: List<Diagnostic>
        field = mutableListOf<Diagnostic>()

    /** The spans of the constructs being analyzed, innermost last; see [at]. */
    private val enclosing = ArrayDeque<SourceSpan>()

    /** The span of the expression analyzed last; a type mismatch is usually reported right after it. */
    var lastAnalyzed: SourceSpan? = null

    /**
     * Runs [block] for the construct at [span]: a diagnostic without a span reported meanwhile gets the span of
     * the innermost such construct, so that every diagnostic has a position (the IDE plugin shows them there).
     */
    fun <T> at(span: SourceSpan?, block: () -> T): T {
        if (span == null) return block()
        enclosing.addLast(span)
        try {
            return block()
        } finally {
            enclosing.removeLast()
        }
    }

    /** The span of the innermost construct being analyzed, see [at]. */
    val current: SourceSpan? get() = enclosing.lastOrNull()

    /** [lastAnalyzed], if it is within the construct being analyzed; [current] otherwise. */
    val lastAnalyzedOrCurrent: SourceSpan?
        get() {
            val last = lastAnalyzed ?: return current
            val outer = current ?: return last
            fun key(line: Int, col: Int) = line.toLong() shl 32 or col.toLong()
            val inside = last.source == outer.source &&
                key(last.startLine, last.startCol) >= key(outer.startLine, outer.startCol) &&
                key(last.endLine, last.endCol) <= key(outer.endLine, outer.endCol)
            return if (inside) last else outer
        }

    fun diag(diagnostic: Diagnostic) {
        diagnostics += if (diagnostic.span == null && current != null) diagnostic.copy(span = current) else diagnostic
    }

    fun hasErrors(): Boolean = diagnostics.any { it.severity == Severity.ERROR }
    fun hasWarning(): Boolean = diagnostics.any { it.severity == Severity.WARNING }

    fun clear() = diagnostics.clear()

    fun report() {
        for (d in diagnostics.sortedWith(compareBy({ it.span?.startLine ?: 0 }, { it.span?.startCol ?: 0 }))) {
            val loc = d.span?.toString() ?: "?"
            val label = when (d.severity) {
                Severity.ERROR -> "error"
                Severity.WARNING -> "warning"
                Severity.INFO -> "info"
            }
            val color = if (d.severity == Severity.ERROR) ANSI_RED else if (d.severity == Severity.WARNING) ANSI_YELLOW else ANSI_WHITE
            println("$color[$loc] $label ${d.code}: ${d.message}$ANSI_RESET")
        }
        val hasError = hasErrors()
        val hasWarning = hasWarning()
        if (hasError || hasWarning) {
            println()
        }
        if (hasErrors()) {
            println("$ANSI_RED${diagnostics.count { it.severity == Severity.ERROR }} error(s).$ANSI_RESET")
        }
        if (hasWarning()) {
            println("$ANSI_YELLOW${diagnostics.count { it.severity == Severity.WARNING }} warning(s).$ANSI_RESET")
        }
    }
}
