package com.leko.forge.intellij.structure

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IdFilter
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.leko.forge.intellij.ForgeFileType
import com.leko.forge.intellij.psi.ForgeDeclaredName

/** The names the .forge files of the project declare (see [ForgeSymbols.SYMBOL_ROLES]), for Go to Symbol and Go to Class. */
class ForgeSymbolIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = 1
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(ForgeFileType)

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
        PsiTreeUtil.findChildrenOfType(content.psiFile, ForgeDeclaredName::class.java)
            .filter { it.role in ForgeSymbols.SYMBOL_ROLES || ForgeSymbols.isNodeChild(it) }
            .mapNotNull { it.name }
            .associateWith { null }
    }

    companion object {
        val NAME: ID<String, Void> = ID.create("forge.symbols")
    }
}

/** Go to Symbol (and, with [types], Go to Class) over [ForgeSymbolIndex]. */
open class ForgeGotoContributor(private val types: Boolean) : ChooseByNameContributorEx {

    private fun accepted(name: ForgeDeclaredName): Boolean =
        if (types) name.role in ForgeSymbols.TYPE_ROLES else name.role in ForgeSymbols.SYMBOL_ROLES || ForgeSymbols.isNodeChild(name)

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        FileBasedIndex.getInstance().processAllKeys(ForgeSymbolIndex.NAME, processor, scope, filter)
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val manager = PsiManager.getInstance(parameters.project)
        val files = FileBasedIndex.getInstance().getContainingFiles(ForgeSymbolIndex.NAME, name, parameters.searchScope)
        for (file in files) {
            val psi = manager.findFile(file) ?: continue
            for (declared in PsiTreeUtil.findChildrenOfType(psi, ForgeDeclaredName::class.java)) {
                if (declared.name == name && accepted(declared) && !processor.process(declared)) return
            }
        }
    }
}

class ForgeGotoSymbolContributor : ForgeGotoContributor(types = false)
class ForgeGotoClassContributor : ForgeGotoContributor(types = true)
