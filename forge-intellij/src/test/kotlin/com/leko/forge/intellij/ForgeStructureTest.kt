package com.leko.forge.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.CommonProcessors
import com.intellij.util.indexing.FindSymbolParameters
import com.leko.forge.intellij.structure.ForgeGotoClassContributor
import com.leko.forge.intellij.structure.ForgeGotoSymbolContributor

class ForgeStructureTest : BasePlatformTestCase() {

    private val program = """
        node Expr
        node Num(value: int): Expr
        enum Type { INT, ERROR(msg: str) }
        record Diagnostic { msg: str, line: int }
        syn Expr.type: Type
        syn Num.type: Type = Type.INT
        fun twice(x: int): int = x * 2
        parser Lang skip '\s+'
        rule Lang.expr: Expr
    """.trimIndent()

    fun testStructureView() {
        val file = myFixture.configureByText("a.forge", program) as com.leko.forge.intellij.parser.ForgeFile
        val model = com.leko.forge.intellij.structure.ForgeStructureViewModel(file, myFixture.editor)
        fun print(element: com.intellij.ide.util.treeView.smartTree.TreeElement, depth: Int): String =
            "  ".repeat(depth) + element.presentation.presentableText + "\n" +
                element.children.joinToString("") { print(it, depth + 1) }
        assertEquals("""
            |a.forge
            |  node Expr
            |  node Num(value: int): Expr
            |    value: int
            |  enum Type { INT, ERROR(msg: str) }
            |    INT
            |    ERROR(msg: str)
            |  record Diagnostic { msg: str, line: int }
            |    msg: str
            |    line: int
            |  syn Expr.type: Type
            |  syn Num.type: Type
            |  fun twice(x: int): int
            |  parser Lang skip '\s+'
            |  rule Lang.expr: Expr
            |""".trimMargin(), print(model.root, 0))
        model.dispose()
    }

    private fun goTo(contributor: com.intellij.navigation.ChooseByNameContributorEx, name: String): List<String> {
        val scope = GlobalSearchScope.projectScope(project)
        val names = CommonProcessors.CollectProcessor<String>()
        contributor.processNames(names, scope, null)
        if (name !in names.results) return emptyList()
        val items = CommonProcessors.CollectProcessor<NavigationItem>()
        contributor.processElementsWithName(name, items, FindSymbolParameters.wrap(name, scope))
        return items.results.map { (it.presentation?.locationString ?: "") }
    }

    fun testGoToSymbolAndClassAcrossFiles() {
        myFixture.addFileToProject("a.forge", program)
        myFixture.addFileToProject("lib/b.forge", "node Stmt\nsyn Stmt.type: str")
        // every `type` of the project: the attributes of both files
        val types = goTo(ForgeGotoSymbolContributor(), "type")
        assertEquals(types.toString(), 3, types.size)
        assertTrue(types.toString(), types.any { "b.forge" in it })
        assertEquals(1, goTo(ForgeGotoSymbolContributor(), "twice").size)
        assertEquals(1, goTo(ForgeGotoSymbolContributor(), "ERROR").size)
        // locals are no symbols
        assertEquals(0, goTo(ForgeGotoSymbolContributor(), "x").size)
        // Go to Class: the types only
        assertEquals(1, goTo(ForgeGotoClassContributor(), "Stmt").size)
        assertEquals(1, goTo(ForgeGotoClassContributor(), "Lang").size)
        assertEquals(0, goTo(ForgeGotoClassContributor(), "twice").size)
    }
}
