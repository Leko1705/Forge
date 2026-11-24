package com.leko.forge.intellij.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import com.leko.forge.intellij.ForgeFileType
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeNameRole
import com.leko.forge.intellij.parser.ForgeNameType
import com.leko.forge.intellij.semantics.ForgeImports
import com.leko.forge.intellij.semantics.ForgeResolution
import com.leko.forge.intellij.semantics.ForgeSemantics

/** A name: one IDENT token with its role in the grammar. */
sealed class ForgeNameElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val role: ForgeNameRole get() = (node.elementType as ForgeNameType).role

    /** The IDENT token. */
    val identifier: PsiElement? get() = node.findChildByType(ForgeTokenTypes.IDENT)?.psi

    /** What the compiler resolved this name to; `null` if it did not, or the program can not be analyzed now. */
    val resolution: ForgeResolution? get() = ForgeSemantics.of(containingFile).resolutions[textRange.startOffset]

    override fun getName(): String? = identifier?.text

    protected fun rename(name: String): PsiElement {
        val old = identifier ?: throw IncorrectOperationException("no name")
        old.replace(ForgeElementFactory.identifier(project, name))
        return this
    }
}

/** A name that declares something: a node, an attribute, a parameter, ... Find Usages starts here. */
class ForgeDeclaredName(node: ASTNode) : ForgeNameElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = identifier
    override fun setName(name: String): PsiElement = rename(name)
    override fun getTextOffset(): Int = textRange.startOffset
}

/** A name that uses a declaration; Ctrl+Click goes to it. */
class ForgeNameReference(node: ASTNode) : ForgeNameElement(node) {
    override fun getReference(): PsiReference? = ForgeReference(this)

    fun handleRename(name: String): PsiElement = rename(name)
}

class ForgeReference(element: ForgeNameReference) :
    PsiReferenceBase<ForgeNameReference>(element, TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        val resolution = element.resolution ?: return null
        val file = ForgeImports.fileOf(resolution.source, element.containingFile) ?: return null
        return findDeclaration(file, resolution)
    }

    override fun handleElementRename(newElementName: String): PsiElement = element.handleRename(newElementName)

    companion object {
        /** The name declared at the position of [resolution] in [file]: the name there, or the one in the declaration starting there. */
        fun findDeclaration(file: PsiFile, resolution: ForgeResolution): PsiElement? {
            val text = file.viewProvider.contents
            var offset = 0
            var line = 1
            while (line < resolution.line) {
                offset = text.indexOf('\n', offset).takeIf { it >= 0 }?.plus(1) ?: return null
                line++
            }
            offset += resolution.column
            if (offset >= text.length) return null
            val leaf = file.findElementAt(offset) ?: return null
            // the outermost element starting at the position: the name itself, or its declaration
            var scope: PsiElement = leaf
            while (scope.parent != null && scope.parent !is PsiFile && scope.parent.textRange.startOffset == offset) scope = scope.parent
            val names = PsiTreeUtil.findChildrenOfType(scope, ForgeNameElement::class.java).toList() +
                listOfNotNull(scope as? ForgeNameElement)
            return names.firstOrNull { it is ForgeDeclaredName && it.name == resolution.name }
                ?: names.firstOrNull { it.name == resolution.name }
                ?: scope
        }
    }
}

object ForgeElementFactory {
    /** An IDENT token with [name]. */
    fun identifier(project: Project, name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project).createFileFromText("dummy.forge", ForgeFileType, "const $name = 0")
        val ident = PsiTreeUtil.collectElements(file) { it.node.elementType == ForgeTokenTypes.IDENT }.firstOrNull()
        if (ident == null || ident.text != name) throw IncorrectOperationException("'$name' is not a name")
        return ident
    }
}
