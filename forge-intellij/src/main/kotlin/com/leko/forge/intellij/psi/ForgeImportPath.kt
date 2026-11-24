package com.leko.forge.intellij.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.psi.util.PsiTreeUtil
import com.leko.forge.intellij.ForgeFileType
import com.leko.forge.intellij.parser.ForgeElementTypes

/**
 * The path of `import "jvm.forge"`, relative to the importing file: Ctrl+Click opens the imported file, completion
 * offers directories and .forge files, and renaming or moving the file updates the path.
 */
class ForgeImportPath(node: ASTNode) : ASTWrapperPsiElement(node) {

    /** The path without the quotes. */
    val path: String get() = text.removeSurrounding("\"")

    override fun getReferences(): Array<PsiReference> {
        // `import "std"`: the standard library, which is no file
        if (path == "std" || text.length < 2) return PsiReference.EMPTY_ARRAY
        val set = object : FileReferenceSet(path, this, 1, null, true, true, arrayOf(ForgeFileType)) {
            // completion offers the directories and the .forge files
            override fun getReferenceCompletionFilter(): Condition<PsiFileSystemItem> =
                Condition { it.isDirectory || it.virtualFile?.extension == ForgeFileType.defaultExtension }
        }
        return set.allReferences.map { it as PsiReference }.toTypedArray()
    }
}

/** Changes the text of an import path, for the file references of [ForgeImportPath]. */
class ForgeImportPathManipulator : AbstractElementManipulator<ForgeImportPath>() {
    override fun handleContentChange(element: ForgeImportPath, range: TextRange, newContent: String): ForgeImportPath {
        val text = range.replace(element.text, newContent)
        val file = PsiFileFactory.getInstance(element.project).createFileFromText("dummy.forge", ForgeFileType, "import $text")
        val path = PsiTreeUtil.collectElements(file) { it.node.elementType == ForgeElementTypes.IMPORT_PATH }.first()
        return element.replace(path) as ForgeImportPath
    }

    override fun getRangeInElement(element: ForgeImportPath): TextRange =
        if (element.textLength >= 2) TextRange(1, element.textLength - 1) else TextRange(0, element.textLength)
}
