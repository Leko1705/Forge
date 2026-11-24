package com.leko.forge.intellij.semantics

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.leko.forge.ide.semantics.ForgeSemantics
import java.io.File

/** The [ForgeSemantics] of the files of the IDE, cached with their PSI. */
object ForgeFileSemantics {

    /** The last semantics of a file that could be analyzed, with its text. */
    private val LAST_GOOD = Key.create<Pair<String, ForgeSemantics>>("forge.lastGoodSemantics")

    /**
     * The semantics of [file], computed once for each version of the program. While the file can not be
     * analyzed (e.g. while a declaration is typed, with syntax errors), those of its last version that could
     * be, moved to where its lines are now: what is on the lines that did not change keeps working.
     */
    fun of(file: PsiFile): ForgeSemantics = CachedValuesManager.getCachedValue(file) {
        val path = file.originalFile.virtualFile?.takeIf { it.isInLocalFileSystem }?.path
        val text = file.text
        val computed = ForgeSemantics.computeOrNull(text, path?.let(::File), ForgeImports)
        val semantics = if (computed != null) {
            file.putUserData(LAST_GOOD, text to computed)
            computed
        } else {
            file.getUserData(LAST_GOOD)?.let { (oldText, old) -> ForgeLineMap.between(oldText, text)?.let(old::moved) } ?: ForgeSemantics.EMPTY
        }
        CachedValueProvider.Result.create(semantics, PsiModificationTracker.MODIFICATION_COUNT)
    }
}
