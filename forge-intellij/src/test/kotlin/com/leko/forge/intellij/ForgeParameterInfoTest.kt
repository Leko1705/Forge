package com.leko.forge.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import com.leko.forge.intellij.completion.ForgeParameterInfoHandler
import com.leko.forge.intellij.completion.ForgeSignature

class ForgeParameterInfoTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // the standard library is in the jar of the compiler, which tests may not read unless allowed
        val jar = com.leko.forge.intellij.semantics.ForgeSemantics::class.java.getResource("/forge/std.forge")!!.path.substringAfter("file:").substringBefore("!")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, jar)
    }

    private val program = """
        node Expr
        node Add(left: Expr, right: Expr): Expr
        enum Type { INT, ERROR(msg: str, line: int) }
        record Diagnostic { msg: str, line: int }
        fun clamp(x: int, lo: int, hi: int): int = if x < lo then lo else if x > hi then hi else x
    """.trimIndent()

    /** The signatures at `<caret>` of [code] (after the program) and the index of the argument of the caret. */
    private fun info(code: String): Pair<List<String>, Int> {
        myFixture.configureByText("a.forge", "$program\n$code")
        val handler = ForgeParameterInfoHandler()
        val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val call = handler.findElementForParameterInfo(create) ?: return emptyList<String>() to -1
        val update = MockUpdateParameterInfoContext(myFixture.editor, myFixture.file)
        handler.updateParameterInfo(call, update)
        val shown = create.itemsToShow.orEmpty().map { item ->
            val ui = MockParameterInfoUIContext<com.intellij.psi.PsiElement>(call)
            ui.currentParameterIndex = update.currentParameter
            handler.updateUI(item as ForgeSignature, ui)
            ui.text
        }
        return shown to update.currentParameter
    }

    fun testAFunction() {
        val (signatures, index) = info("const c = clamp(5, 1, <caret>10)")
        assertEquals(listOf("x: int, lo: int, hi: int"), signatures)
        assertEquals(2, index)
    }

    fun testConstructorsAndEnumConstants() {
        assertEquals(listOf("left: Expr, right: Expr") to 0, info("fun f(e: Expr): Expr = Add(<caret>e, e)"))
        assertEquals(listOf("msg: str, line: int") to 1, info("const d = Diagnostic(\"m\", <caret>1)"))
        assertEquals(listOf("msg: str, line: int") to 0, info("const t = Type.ERROR(<caret>\"m\", 1)"))
    }

    fun testMethodsOfTheStandardLibrary() {
        val (signatures, index) = info("fun f(s: str): str = s.replace(\"a\", <caret>\"b\")")
        assertEquals(listOf("old: str, replacement: str"), signatures)
        assertEquals(1, index)
    }

    fun testWhileTheCallIsTyped() {
        // the program can not be analyzed: the call is found by its name
        val (signatures, index) = info("const c = clamp(5, <caret>")
        assertEquals(listOf("x: int, lo: int, hi: int"), signatures)
        assertEquals(1, index)
    }

    fun testNothingOutsideOfCalls() {
        assertEquals(emptyList<String>() to -1, info("const c = <caret>clamp"))
        // a declaration is no call
        assertEquals(emptyList<String>() to -1, info("node Mul(left: <caret>Expr)"))
    }

    fun testNestedBrackets() {
        // the commas inside the list are not those of the call
        assertEquals(listOf("x: int, lo: int, hi: int") to 1, info("const c = clamp([1, 2, 3].size, <caret>1, 2)"))
    }
}
