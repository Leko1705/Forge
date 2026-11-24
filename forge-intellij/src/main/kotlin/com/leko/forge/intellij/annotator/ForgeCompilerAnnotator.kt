package com.leko.forge.intellij.annotator

import ForgeParser
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.leko.forge.analysis.Dependency
import com.leko.forge.attr.Attr
import com.leko.forge.attr.clearTreeAttributes
import com.leko.forge.context.Context
import com.leko.forge.enter.Enter
import com.leko.forge.enter.MemberEnter
import com.leko.forge.intellij.parser.ForgePsiParser
import com.leko.forge.intellij.semantics.ForgeImports
import com.leko.forge.intellij.semantics.ForgeSemantics
import com.leko.forge.model.SourceSpan
import com.leko.forge.parse.ImportException
import com.leko.forge.parse.parseForgeImports
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.Token
import java.io.File

/** A diagnostic in the editor: [range] in the analyzed text, or the whole file if `null`. */
data class ForgeProblem(val severity: HighlightSeverity, val message: String, val range: TextRange?)

/** The text of a file to analyze, with the file on disk its imports are relative to. */
data class ForgeSource(val text: String, val file: File?)

/**
 * Runs the compiler's analysis (Enter, MemberEnter, Attr, Dependency) on the text of the editor, with the imported
 * files and the standard library, as `forge -f` does, and shows its errors and warnings where the compiler
 * reports them. It runs only when the file has no syntax errors, which the parser reports already.
 */
class ForgeCompilerAnnotator : ExternalAnnotator<ForgeSource, List<ForgeProblem>>() {

    override fun collectInformation(file: PsiFile): ForgeSource? {
        if (PsiTreeUtil.hasErrorElements(file)) return null
        val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path
        return ForgeSource(file.text, path?.let(::File))
    }

    override fun doAnnotate(source: ForgeSource): List<ForgeProblem> = analyze(source)

    override fun apply(file: PsiFile, problems: List<ForgeProblem>, holder: AnnotationHolder) {
        val length = file.textLength
        for (problem in problems) {
            val builder = holder.newAnnotation(problem.severity, problem.message)
            val range = problem.range
            if (range == null || range.endOffset > length) builder.fileLevel() else builder.range(range)
            builder.create()
        }
    }

    companion object {
        private val LOG = logger<ForgeCompilerAnnotator>()


        fun analyze(source: ForgeSource): List<ForgeProblem> {
            val tokens = ForgePsiParser.antlrTokens(source.text)
            val parser = ForgeParser(CommonTokenStream(ListTokenSource(tokens)))
            parser.removeErrorListeners()
            val main = parser.root()
            if (parser.numberOfSyntaxErrors > 0) return emptyList()
            val positions = Positions(source.text, tokens)
            val problems = mutableListOf<ForgeProblem>()

            // the imports this file names: their files must exist
            val imports = main.importDecl().mapNotNull { decl ->
                val path = decl.path.text.let { it.substring(1, it.length - 1) }
                if (path == "std") return@mapNotNull null
                val range = TextRange(decl.path.startIndex, decl.path.stopIndex + 1)
                val base = source.file?.absoluteFile?.parentFile
                if (base == null) {
                    problems += ForgeProblem(HighlightSeverity.WARNING, "imports of a file that is not saved on disk are not checked", range)
                    return@mapNotNull null
                }
                val target = File(base, path)
                if (!target.isFile) {
                    problems += ForgeProblem(HighlightSeverity.ERROR, "can not import '$path': ${target.path} does not exist", range)
                    return@mapNotNull null
                }
                target.canonicalPath to range
            }.toMap()
            if (problems.isNotEmpty()) return problems

            val diagnostics = try {
                synchronized(ForgeSemantics.lock) {
                    try {
                        // without a file, the imports are skipped above; the standard library is always added
                        val root = (source.file ?: File("<editor>")).parseForgeImports(main, ForgeImports::read)
                        val context = Context()
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
                return listOf(ForgeProblem(HighlightSeverity.ERROR, e.message ?: "can not import", imports.values.firstOrNull()))
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.warn("the Forge analysis failed", e)
                return listOf(ForgeProblem(HighlightSeverity.WARNING, "the Forge analysis failed: $e", null))
            }

            val mainPath = source.file?.canonicalPath
            for (d in diagnostics) {
                val severity = when (d.severity) {
                    Severity.ERROR -> HighlightSeverity.ERROR
                    Severity.WARNING -> HighlightSeverity.WARNING
                    Severity.INFO -> HighlightSeverity.WEAK_WARNING
                }
                val message = "${d.message} [${d.code}]"
                val span = d.span
                val spanPath = span?.source?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                problems += when {
                    span == null || span.startLine <= 0 -> ForgeProblem(severity, message, null)
                    // in this file: the main tree has the path of the file, or no source name without a file
                    span.source == null || spanPath == mainPath ->
                        ForgeProblem(severity, message, positions.range(span))
                    // in an imported file: on its import, or on the file if it is imported indirectly
                    else -> ForgeProblem(severity, "${File(span.source!!).name}:${span.startLine}:${span.startCol}: $message", imports[spanPath])
                }
            }
            return problems
        }
    }
}

/** Offsets of the lines and columns of the compiler's spans; ANTLR's lines start at 1, its columns at 0. */
private class Positions(private val text: String, private val tokens: List<Token>) {
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
    fun range(span: SourceSpan): TextRange {
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
        return TextRange(start, endOffset.coerceAtLeast(start))
    }
}
