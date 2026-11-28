package com.leko.forge.intellij

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ForgeCompletionTest : BasePlatformTestCase() {

    private val program = """
        node Expr
        node Num(value: int): Expr
        node Add(left: Expr, right: Expr): Expr
        enum Type { INT, ERROR(msg: str) }
        record Diagnostic { msg: str, line: int }
        const limit = 3
        syn Expr.type: Type
        circular(0) Expr.size: int = 1
        val Type.isError: bool = match this { Type.ERROR(_) -> true  _ -> false }
        fun twice(x: int): int = x * 2
        inh depth: int = 0
        inh Add.depth { left <- this.depth + 1  right <- this.depth + 1 }
        parser Lang skip '\s+'
        rule Lang.expr: Expr
        rule Lang.number: str -> '[0-9]+'
    """.trimIndent()

    /** The completions at `<caret>` of [code], added at the end of the program. */
    private fun complete(code: String): List<String> {
        myFixture.configureByText("a.forge", "$program\n$code")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun assertContains(completions: List<String>, vararg expected: String) {
        for (e in expected) assertTrue("'$e' in $completions", e in completions)
    }

    private fun assertNotContains(completions: List<String>, vararg unexpected: String) {
        for (u in unexpected) assertFalse("'$u' not in $completions", u in completions)
    }

    fun testMembersOfANode() {
        val members = complete("syn Add.type: Type = this.<caret>")
        // fields, attributes (also of the interface), inh attributes on `this`
        assertContains(members, "left", "right", "type", "size", "depth")
        // not the members of other types
        assertNotContains(members, "value", "msg", "isError", "upper")
    }

    fun testMembersOfAChild() {
        val members = complete("syn Add.type: Type = this.left.<caret>")
        assertContains(members, "type", "size")
        // an inh attribute is read on `this` only; Expr has no fields
        assertNotContains(members, "depth", "left", "value")
    }

    fun testMembersOfOtherTypes() {
        assertContains(complete("syn Add.ok: bool = this.type.<caret>"), "isError")
        assertContains(complete("fun f(d: Diagnostic): int = d.<caret>"), "msg", "line")
        // the standard library: methods and properties of str
        assertContains(complete("fun f(s: str): str = s.<caret>"), "upper", "trim", "size")
        assertNotContains(complete("fun f(s: str): str = s.<caret>"), "msg", "isError")
    }

    fun testTypedPrefix() {
        myFixture.configureByText("a.forge", "$program\nsyn Add.type: Type = this.ri<caret>")
        myFixture.completeBasic()
        // the only match is inserted
        myFixture.checkResult("$program\nsyn Add.type: Type = this.right")
    }

    fun testMethodsGetParentheses() {
        myFixture.configureByText("a.forge", "$program\nfun f(s: str): str = s.upp<caret>")
        myFixture.completeBasic()
        myFixture.checkResult("$program\nfun f(s: str): str = s.upper()")
    }

    fun testEnumConstants() {
        assertContains(complete("syn Num.type: Type = Type.<caret>"), "INT", "ERROR")
        assertContains(complete("fun f(t: Type): bool = match t { Type.<caret> -> true  _ -> false }"), "INT", "ERROR")
    }

    fun testNamesInExpressions() {
        val names = complete("fun f(n: int): int = <caret>")
        assertContains(names, "n", "limit", "twice", "Num", "Diagnostic", "Type", "if", "match", "this")
        // a local of another declaration is not in scope
        assertNotContains(names, "x")
    }

    fun testTypes() {
        val types = complete("fun f(n: <caret>")
        assertContains(types, "int", "str", "bool", "List", "Expr", "Num", "Type", "Diagnostic")
        assertNotContains(types, "twice", "limit")
    }

    fun testWhatDeclarationsName() {
        assertContains(complete("syn <caret>"), "Expr", "Num", "Add")
        // the attributes a node implements
        assertContains(complete("syn Num.<caret>"), "type", "size")
        assertContains(complete("inh Num.<caret>"), "depth")
        assertContains(complete("rule Lang.<caret>"), "expr", "number")
        assertContains(complete("node Mul(left: Expr, right: Expr): <caret>"), "Expr")
    }

    fun testTopLevelKeywords() {
        assertContains(complete("<caret>"), "node", "syn", "inh", "fun", "rule", "enum", "record", "flow")
    }

    fun testParserRules() {
        assertContains(complete("rule Lang.expr: Num -> n: <caret>"), "number", "expr")
        assertContains(complete("rule Lang.expr: Expr @<caret>"), "infixLeft", "prefix")
    }

    fun testNothingInCommentsAndStrings() {
        assertEquals(emptyList<String>(), complete("// this.<caret>"))
        assertEquals(emptyList<String>(), complete("const s = \"this.<caret>\""))
    }

    /** Completes [code] (after the program) and chooses [name]. */
    private fun choose(code: String, name: String) {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText("a.forge", "$program\n$code")
        val elements = myFixture.completeBasic()
        if (elements != null) {
            myFixture.lookup.currentItem = elements.first { it.lookupString == name }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
    }

    private fun nextField() = TemplateManagerImpl.getTemplateState(myFixture.editor)!!.nextTab()

    fun testALambdaFollowsAFunctionOfOne() {
        choose("fun f(xs: List<int>): List<int> = xs.ma<caret>", "map")
        myFixture.checkResult("$program\nfun f(xs: List<int>): List<int> = xs.map { <caret> }")
    }

    fun testALambdaWithParametersFollowsTheArguments() {
        choose("fun f(xs: List<int>): int = xs.fol<caret>", "fold")
        // first the arguments, then the parameters of the lambda, then its body
        myFixture.checkResult("$program\nfun f(xs: List<int>): int = xs.fold(<caret>) { acc, i ->  }")
        myFixture.type("0")
        nextField()
        nextField()
        nextField()
        myFixture.checkResult("$program\nfun f(xs: List<int>): int = xs.fold(0) { acc, i -> <caret> }")
        assertNull(TemplateManagerImpl.getTemplateState(myFixture.editor))
    }

    fun testNoLambdaWhereTheCallIsWritten() {
        choose("fun f(xs: List<int>): List<int> = xs.ma<caret> { it }", "map")
        myFixture.checkResult("$program\nfun f(xs: List<int>): List<int> = xs.map<caret> { it }")
    }
}
