package com.leko.forge.ide.diagnostics

import ForgeParser
import com.leko.forge.analysis.Dependency
import com.leko.forge.attr.Attr
import com.leko.forge.attr.clearTreeAttributes
import com.leko.forge.context.Context
import com.leko.forge.enter.Enter
import com.leko.forge.enter.MemberEnter
import com.leko.forge.ide.ForgeHost
import com.leko.forge.ide.parser.ForgeAntlr
import com.leko.forge.ide.semantics.ForgeSemantics
import com.leko.forge.model.SourceSpan
import com.leko.forge.parse.ImportException
import com.leko.forge.parse.parseForgeImports
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.Token
import java.io.File
import java.util.logging.Level

/** How bad a [ForgeProblem] is; [INFO] for the compiler's notes. */
enum class ForgeSeverity { ERROR, WARNING, INFO }

/** The characters from [start] to [end] (exclusive) of a text. */
data class ForgeTextRange(val start: Int, val end: Int) {
    val length: Int get() = end - start

    /** Whether [other] is within this range. */
    fun contains(other: ForgeTextRange): Boolean = start <= other.start && other.end <= end
}

/**
 * A diagnostic in the editor: [range] in the analyzed text, or the whole file if `null`. [text] is the compiler's
 * message without its code, for quick fixes; `null` for problems the editor support finds.
 */
data class ForgeProblem(val severity: ForgeSeverity, val message: String, val range: ForgeTextRange?, val text: String? = null)

/** The text of a file to analyze, with the file on disk its imports are relative to. */
data class ForgeSource(val text: String, val file: File?)

/**
 * Runs the compiler's analysis (Enter, MemberEnter, Attr, Dependency) on the text of an editor, with the imported
 * files and the standard library, as `forge -f` does, and reports its errors and warnings where the compiler
 * reports them. It reports nothing for a file with syntax errors, which the parser reports already.
 */
object ForgeDiagnostics {

    fun analyze(source: ForgeSource, host: ForgeHost): List<ForgeProblem> {
        val tokens = ForgeAntlr.tokens(source.text)
        val parser = ForgeParser(CommonTokenStream(ListTokenSource(tokens)))
        parser.removeErrorListeners()
        val main = parser.root()
        // the expressions in strings, and `where`s as the compiler sees them
        val interpolationErrors = com.leko.forge.parse.postParse(main, object : org.antlr.v4.runtime.BaseErrorListener() {})
        if (parser.numberOfSyntaxErrors > 0 || interpolationErrors > 0) return emptyList()
        val positions = Positions(source.text, tokens)
        val problems = mutableListOf<ForgeProblem>()

        // the imports this file names: their files must exist
        val imports = main.importDecl().mapNotNull { decl ->
            val path = decl.path.text.let { it.substring(1, it.length - 1) }
            if (path == "std") return@mapNotNull null
            val range = ForgeTextRange(decl.path.startIndex, decl.path.stopIndex + 1)
            val base = source.file?.absoluteFile?.parentFile
            if (base == null) {
                problems += ForgeProblem(ForgeSeverity.WARNING, "imports of a file that is not saved on disk are not checked", range)
                return@mapNotNull null
            }
            val target = File(base, path)
            if (!target.isFile) {
                problems += ForgeProblem(ForgeSeverity.ERROR, "can not import '$path': ${target.path} does not exist", range)
                return@mapNotNull null
            }
            target.canonicalPath to range
        }.toMap()
        if (problems.isNotEmpty()) return problems

        val diagnostics = try {
            synchronized(ForgeSemantics.lock) {
                try {
                    // without a file, the imports are skipped above; the standard library is always added
                    val root = (source.file ?: File("<editor>")).parseForgeImports(main, host::read)
                    val context = Context()
                    com.leko.forge.enter.Modules.instance(context).resolve(root)
                    Enter.instance(context).enter(root)
                    MemberEnter.instance(context).enter(root)
                    Attr.instance(context).attr(root)
                    Dependency.instance(context).check(root)
                    Log.instance(context).diagnostics.toList()
                } finally {
                    clearTreeAttributes()
                }
            }
        } catch (e: ImportException) {
            return listOf(ForgeProblem(ForgeSeverity.ERROR, e.message ?: "can not import", imports.values.firstOrNull()))
        } catch (e: Exception) {
            if (host.cancels(e)) throw e
            host.log(Level.WARNING, "the Forge analysis failed", e)
            return listOf(ForgeProblem(ForgeSeverity.WARNING, "the Forge analysis failed: $e", null))
        }

        val mainPath = source.file?.canonicalPath
        for (d in diagnostics) {
            val severity = when (d.severity) {
                Severity.ERROR -> ForgeSeverity.ERROR
                Severity.WARNING -> ForgeSeverity.WARNING
                Severity.INFO -> ForgeSeverity.INFO
            }
            val message = "${d.message} [${d.code}]"
            val span = d.span
            val spanPath = span?.source?.let { runCatching { File(it).canonicalPath }.getOrNull() }
            problems += when {
                span == null || span.startLine <= 0 -> ForgeProblem(severity, message, null)
                // in this file: the main tree has the path of the file, or no source name without a file
                span.source == null || spanPath == mainPath ->
                    ForgeProblem(severity, message, positions.range(span), d.message)
                // in an imported file: on its import, or on the file if it is imported indirectly
                else -> ForgeProblem(severity, "${File(span.source!!).name}:${span.startLine}:${span.startCol}: $message", imports[spanPath])
            }
        }
        return problems
    }
}

/** Offsets of the lines and columns of the compiler's spans; ANTLR's lines start at 1, its columns at 0. */
private class Positions(private val text: String, tokens: List<Token>) {
    private val lineStarts = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }
    private val tokenAt = tokens.filter { it.type != Token.EOF }.associateBy { it.startIndex }

    private fun offset(line: Int, column: Int): Int {
        val start = lineStarts.getOrNull(line - 1) ?: return text.length
        return (start + column).coerceIn(0, text.length)
    }

    /**
     * The range of [span]. The span of a token ends after it, the span of a rule at the start of its last token,
     * which the range includes.
     */
    fun range(span: SourceSpan): ForgeTextRange {
        val start = offset(span.startLine, span.startCol)
        val end = offset(span.endLine, span.endCol)
        val first = tokenAt[start]
        val last = tokenAt[end]
        val endOffset = when {
            first != null && first.stopIndex + 1 == end -> end
            last != null && end >= start -> last.stopIndex + 1
            end > start -> end
            first != null -> first.stopIndex + 1
            else -> (start + 1).coerceAtMost(text.length)
        }
        return ForgeTextRange(start, endOffset.coerceAtLeast(start))
    }
}
