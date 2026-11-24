package com.leko.forge.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ForgeFormatterTest : BasePlatformTestCase() {

    private fun format(text: String): String {
        val file = myFixture.configureByText("a.forge", text)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
        return myFixture.editor.document.text
    }

    fun testIndentation() {
        val messy = """
            |   node Expr
            |node Num(value: int): Expr
            |syn Num.type: str = match this.value {
            |        0 -> "zero"
            |  _ -> "other"
            |      }
            |inh Add.depth {
            |left <- this.depth + 1
            |       right <- this.depth + 1
            |}
            |rule Kiln.function: Function ->
            |'fun' name:ident '\(' params:params? '\)'
            |          { Function(name, params ?: []) }
            |record Diagnostic {
            |  msg: str,
            |      line: int
            |}
            |""".trimMargin()
        val expected = """
            |node Expr
            |node Num(value: int): Expr
            |syn Num.type: str = match this.value {
            |    0 -> "zero"
            |    _ -> "other"
            |}
            |inh Add.depth {
            |    left <- this.depth + 1
            |    right <- this.depth + 1
            |}
            |rule Kiln.function: Function ->
            |    'fun' name:ident '\(' params:params? '\)'
            |    { Function(name, params ?: []) }
            |record Diagnostic {
            |    msg: str,
            |    line: int
            |}
            |""".trimMargin()
        assertEquals(expected, format(messy))
    }

    fun testSpacing() {
        assertEquals("fun f(a: int, b: int): int = if a == b then a else b",
            format("fun f(a: int ,b: int): int=if a==b then a else b"))
        // what means something else in rules and types stays as it is
        assertEquals("rule P.r: List<str> -> l:x* r:y+ s:z? { [l] ++ r }",
            format("rule P.r: List<str> -> l:x* r:y+ s:z? { [l]++r }"))
        assertEquals("syn A.b: int = this.c.d", format("syn A.b: int = this . c . d"))
    }

    fun testFormattedFilesStayAsTheyAre() {
        val root = File(System.getProperty("forge.root"))
        for (path in listOf("src/test/forge/parser.forge", "src/test/forge/flow.forge", "src/main/resources/forge/std.forge")) {
            val text = File(root, path).readText()
            assertEquals(path, text, format(text))
        }
    }

    fun testEnterIndents() {
        myFixture.configureByText("a.forge", "syn Num.type: str = match this.value {<caret>}")
        myFixture.type("\n")
        assertEquals("syn Num.type: str = match this.value {\n    \n}", myFixture.editor.document.text)
        myFixture.configureByText("a.forge", "rule P.r: str -><caret>")
        myFixture.type("\n")
        assertEquals("rule P.r: str ->\n    ", myFixture.editor.document.text)
        myFixture.configureByText("a.forge", "node A(n: int)<caret>")
        myFixture.type("\n")
        assertEquals("node A(n: int)\n", myFixture.editor.document.text)
    }
}
