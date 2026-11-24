package com.leko.forge.intellij.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.ForgeIcons
import com.leko.forge.intellij.parser.ForgeFile
import com.leko.forge.intellij.parser.ForgeRuleType
import com.leko.forge.intellij.psi.ForgeDeclaredName
import javax.swing.Icon

/** The structure view: the declarations of the file; fields, children and enum constants below their type. */
class ForgeStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is ForgeFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = ForgeStructureViewModel(psiFile, editor)
        }
    }
}

class ForgeStructureViewModel(file: ForgeFile, editor: Editor?) :
    StructureViewModelBase(file, editor, ForgeFileElement(file)), StructureViewModel.ElementInfoProvider {

    init {
        withSuitableClasses(ForgeDeclaredName::class.java)
    }

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element is ForgeDeclarationElement && element.children.isEmpty()
}

/** The declarations of a file: the names that the children of the Root rule declare. */
private fun declarations(file: ForgeFile): List<ForgeDeclaredName> {
    val root = file.node.getChildren(null).firstOrNull { (it.elementType as? ForgeRuleType)?.ruleName == "Root" } ?: return emptyList()
    return root.getChildren(null).mapNotNull { decl ->
        decl.psi?.children?.filterIsInstance<ForgeDeclaredName>()?.firstOrNull()
    }
}

private class ForgeFileElement(private val file: ForgeFile) : StructureViewTreeElement {
    override fun getValue(): Any = file
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = file.name
        override fun getIcon(unused: Boolean): Icon = ForgeIcons.FILE
    }
    override fun getChildren(): Array<TreeElement> = declarations(file).map(::ForgeDeclarationElement).toTypedArray()
    override fun navigate(requestFocus: Boolean) = file.navigate(requestFocus)
    override fun canNavigate(): Boolean = file.canNavigate()
    override fun canNavigateToSource(): Boolean = file.canNavigateToSource()
}

class ForgeDeclarationElement(private val name: ForgeDeclaredName) : StructureViewTreeElement {
    override fun getValue(): Any = name
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = when {
            name.role in setOf(ForgeNameRole.FIELD, ForgeNameRole.ENUM_CONSTANT) || ForgeSymbols.isNodeChild(name) ->
                name.parent?.text?.replace(Regex("\\s+"), " ") ?: name.text
            else -> ForgeSymbols.header(name)
        }
        override fun getIcon(unused: Boolean): Icon = ForgeSymbols.icon(name)
    }

    /** The fields of a record or a node, the constants of an enum; the declarations of a module, the members of a trait. */
    override fun getChildren(): Array<TreeElement> {
        val decl: PsiElement = name.parent ?: return emptyArray()
        if (name.role == ForgeNameRole.MODULE || name.role == ForgeNameRole.TRAIT) {
            return decl.children.mapNotNull { it.children.filterIsInstance<ForgeDeclaredName>().firstOrNull() }
                .map(::ForgeDeclarationElement).toTypedArray()
        }
        if (name.role != ForgeNameRole.NODE_DECLARATION && name.role != ForgeNameRole.TYPE_DECLARATION) return emptyArray()
        return PsiTreeUtil.findChildrenOfType(decl, ForgeDeclaredName::class.java)
            .filter { it !== name && (it.role == ForgeNameRole.FIELD || it.role == ForgeNameRole.ENUM_CONSTANT || ForgeSymbols.isNodeChild(it)) }
            .filter { it.parent?.parent === decl }
            .map(::ForgeDeclarationElement).toTypedArray()
    }

    override fun navigate(requestFocus: Boolean) = (name as Navigatable).navigate(requestFocus)
    override fun canNavigate(): Boolean = name.canNavigate()
    override fun canNavigateToSource(): Boolean = name.canNavigateToSource()
}
