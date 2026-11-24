package com.leko.forge.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.leko.forge.intellij.annotator.ForgeCompilerAnnotator
import com.leko.forge.intellij.annotator.ForgeSource

class ForgeQuickFixTest : BasePlatformTestCase() {

    private fun errors(text: String) =
        ForgeCompilerAnnotator.analyze(ForgeSource(text, null)).filter { it.severity == HighlightSeverity.ERROR }

    /** Applies the fix [name] at `<caret>` of [before]; the result must be [after], a program without errors. */
    private fun fix(before: String, name: String, after: String) {
        myFixture.configureByText("a.forge", before)
        myFixture.doHighlighting()
        myFixture.launchAction(myFixture.findSingleIntention(name))
        myFixture.checkResult(after)
        assertEquals(emptyList<Any>(), errors(after))
    }

    fun testAddThis() {
        fix(
            "node Add(left: int, right: int)\nsyn Add.sum: int = <caret>left + this.right",
            "Replace 'left' with 'this.left'",
            "node Add(left: int, right: int)\nsyn Add.sum: int = this.left + this.right",
        )
        // an attribute, in an inh equation
        fix(
            "node A(b: B)\nnode B(n: int)\ninh depth: int = 0\ninh A.depth { b <- <caret>depth + 1 }",
            "Replace 'depth' with 'this.depth'",
            "node A(b: B)\nnode B(n: int)\ninh depth: int = 0\ninh A.depth { b <- this.depth + 1 }",
        )
    }

    fun testNoThisWhereItWouldNotHelp() {
        myFixture.configureByText("a.forge", "node Add(left: int)\nfun f(): int = <caret>nothing")
        myFixture.doHighlighting()
        assertTrue(myFixture.filterAvailableIntentions("Replace").isEmpty())
    }

    fun testCreateNode() {
        fix("node Program(stmts: List<<caret>Stmt>)", "Create node 'Stmt'", "node Program(stmts: List<Stmt>)\nnode Stmt\n")
        fix("node A\nconst leaf = <caret>Leaf()", "Create node 'Leaf'", "node A\nconst leaf = Leaf()\nnode Leaf()\n")
    }

    fun testImplementSyn() {
        fix(
            "node Expr\nnode Num(value: int): Expr\nnode Add(l: Expr, r: Expr): Expr\nsyn Expr.<caret>type: str\nsyn Add.type: str = \"add\"",
            "Implement 'syn Num.type'",
            "node Expr\nnode Num(value: int): Expr\nnode Add(l: Expr, r: Expr): Expr\nsyn Expr.type: str\nsyn Add.type: str = \"add\"\nsyn Num.type: str = fail(\"not implemented\")",
        )
    }
}
