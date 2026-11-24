package com.leko.forge.intellij.semantics

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.leko.forge.parse.STD_SOURCE
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CharStream
import java.io.File

/** The files of a program in the IDE: imported files as the editor has them, and the bundled standard library. */
object ForgeImports {

    /**
     * The text of an imported file: of its editor if it is open (it may not be saved), from disk otherwise. As
     * UTF-16 chars, like the editor, so that the columns of the compiler's spans are offsets in the editor's lines.
     */
    fun read(file: File): CharStream {
        val text = ReadAction.compute<String, RuntimeException> {
            LocalFileSystem.getInstance().findFileByIoFile(file)
                ?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
                ?.text
        } ?: file.readText()
        return ANTLRInputStream(text).apply { name = file.absolutePath }
    }

    /** The standard library bundled with the compiler, read-only. */
    private val stdFile: VirtualFile? by lazy {
        ForgeImports::class.java.getResource("/forge/std.forge")?.let { VfsUtil.findFileByURL(it) }
    }

    /** The file of the source of a [ForgeResolution]: [file] itself for `null`. */
    fun fileOf(source: String?, file: PsiFile, project: Project = file.project): PsiFile? = when (source) {
        null -> file
        STD_SOURCE -> stdFile?.let { PsiManager.getInstance(project).findFile(it) }
        else -> LocalFileSystem.getInstance().findFileByPath(source)?.let { PsiManager.getInstance(project).findFile(it) }
    }
}
