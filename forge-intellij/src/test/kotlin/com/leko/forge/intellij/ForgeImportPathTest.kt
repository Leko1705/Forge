package com.leko.forge.intellij

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ForgeImportPathTest : BasePlatformTestCase() {

    private fun setUpFiles() {
        myFixture.addFileToProject("lib/jvm.forge", "node Code")
        myFixture.addFileToProject("lib/notes.txt", "not forge")
        myFixture.addFileToProject("src/other.forge", "node Other")
    }

    fun testCtrlClickOpensTheImportedFile() {
        setUpFiles()
        val file = myFixture.addFileToProject("src/main.forge", "import \"../lib/jvm.forge\"\nnode A(c: Code)")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("jvm"))
        val target = myFixture.elementAtCaret
        assertTrue(target.toString(), target is PsiFile && target.name == "jvm.forge")
    }

    fun testCompletionOffersDirectoriesAndForgeFiles() {
        setUpFiles()
        val file = myFixture.addFileToProject("src/main.forge", "import \"../lib/\"")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("lib/") + 4)
        myFixture.completeBasic()
        // the .forge file, not notes.txt
        assertEquals(listOf("jvm.forge"), myFixture.lookupElementStrings)
    }

    fun testRenamingTheFileUpdatesTheImport() {
        setUpFiles()
        val file = myFixture.addFileToProject("src/main.forge", "import \"../lib/jvm.forge\"\nnode A(c: Code)")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val jvm = myFixture.findFileInTempDir("lib/jvm.forge")
        myFixture.renameElement(psiManager.findFile(jvm)!!, "bytecode.forge")
        assertEquals("import \"../lib/bytecode.forge\"\nnode A(c: Code)", myFixture.editor.document.text)
    }

    fun testTheStandardLibraryIsNoFile() {
        val file = myFixture.configureByText("a.forge", "import \"std\"\nnode A")
        val path = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, com.leko.forge.intellij.psi.ForgeImportPath::class.java)!!
        assertEquals(0, path.references.size)
    }
}
