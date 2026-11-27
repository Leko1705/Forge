package com.leko.forge.intellij.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.leko.forge.ide.diagnostics.ForgeDiagnostics
import com.leko.forge.ide.diagnostics.ForgeProblem
import com.leko.forge.ide.diagnostics.ForgeSeverity
import com.leko.forge.ide.diagnostics.ForgeSource
import com.leko.forge.ide.diagnostics.ForgeTextRange
import com.leko.forge.intellij.semantics.ForgeImports
import java.io.File

/**
 * Shows the diagnostics of the compiler's analysis ([ForgeDiagnostics]) in the editor, where the compiler reports
 * them. It runs only when the file has no syntax errors, which the parser reports already.
 */
class ForgeCompilerAnnotator : ExternalAnnotator<ForgeSource, List<ForgeProblem>>() {

    override fun collectInformation(file: PsiFile): ForgeSource? {
        if (PsiTreeUtil.hasErrorElements(file)) return null
        val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path
        return ForgeSource(file.text, path?.let(::File))
    }

    override fun doAnnotate(source: ForgeSource): List<ForgeProblem> = ForgeDiagnostics.analyze(source, ForgeImports)

    override fun apply(file: PsiFile, problems: List<ForgeProblem>, holder: AnnotationHolder) {
        val length = file.textLength
        for (problem in problems) {
            if (problem.severity == ForgeSeverity.TODO) {
                // a call of TODO: no problem, but in the color of TODO comments, as Kotlin's TODO()
                val range = problem.range?.takeIf { it.end <= length } ?: continue
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range.textRange)
                    .textAttributes(com.leko.forge.intellij.highlight.ForgeColors.TODO_CALL).create()
                continue
            }
            if (problem.severity == ForgeSeverity.ASSIST) {
                // no problem: only its fixes, where it is
                val range = problem.range?.takeIf { it.end <= length } ?: continue
                var builder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range.textRange)
                for (fix in ForgeQuickFixes.fixesFor(file, problem)) builder = builder.withFix(fix)
                builder.create()
                continue
            }
            var builder = holder.newAnnotation(problem.severity.highlight, problem.message)
            val range = problem.range
            builder = if (range == null || range.end > length) builder.fileLevel() else builder.range(range.textRange)
            for (fix in ForgeQuickFixes.fixesFor(file, problem)) builder = builder.withFix(fix)
            builder.create()
        }
    }
}

/** How IntelliJ shows a problem of this severity. */
val ForgeSeverity.highlight: HighlightSeverity
    get() = when (this) {
        ForgeSeverity.ERROR -> HighlightSeverity.ERROR
        ForgeSeverity.WARNING -> HighlightSeverity.WARNING
        ForgeSeverity.INFO -> HighlightSeverity.WEAK_WARNING
        ForgeSeverity.TODO, ForgeSeverity.ASSIST -> HighlightSeverity.INFORMATION
    }

val ForgeTextRange.textRange: TextRange get() = TextRange(start, end)
