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

    fun diag(diagnostic: Diagnostic) {
        diagnostics += diagnostic
    }

    fun hasErrors(): Boolean = diagnostics.any { it.severity == Severity.ERROR }
    fun hasWarning(): Boolean = diagnostics.any { it.severity == Severity.WARNING }

    fun clear() = diagnostics.clear()

    private var reported = 0

    /** Prints the diagnostics reported since the last call. */
    fun report() {
        val pending = diagnostics.drop(reported)
        reported = diagnostics.size
        for (d in pending.sortedWith(compareBy({ it.span?.startLine ?: 0 }, { it.span?.startCol ?: 0 }))) {
            val loc = d.span?.let { "[$it] " } ?: ""
            val label = when (d.severity) {
                Severity.ERROR -> "error"
                Severity.WARNING -> "warning"
                Severity.INFO -> "info"
            }
            val color = if (d.severity == Severity.ERROR) ANSI_RED else if (d.severity == Severity.WARNING) ANSI_YELLOW else ANSI_WHITE
            println("$color$loc$label ${d.code}: ${d.message}$ANSI_RESET")
        }
    }

    fun summary() {
        if (hasErrors() || hasWarning()) {
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
