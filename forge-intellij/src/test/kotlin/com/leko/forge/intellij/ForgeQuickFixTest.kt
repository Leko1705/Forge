package com.leko.forge.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.leko.forge.ide.diagnostics.ForgeDiagnostics
import com.leko.forge.ide.diagnostics.ForgeSeverity
import com.leko.forge.ide.diagnostics.ForgeSource
import com.leko.forge.intellij.semantics.ForgeImports

class ForgeQuickFixTest : BasePlatformTestCase() {

    private fun errors(text: String) =
        ForgeDiagnostics.analyze(ForgeSource(text, null), ForgeImports).filter { it.severity == ForgeSeverity.ERROR }

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

    fun testCreateMissingDeclarations() {
        fix(
            "fun f(x: int): str = <caret>describe(x)",
            "Create function 'describe'",
            "fun f(x: int): str = describe(x)\n\nfun describe(x: int): str = TODO(\"not yet implemented\")",
        )
        fix(
            "record R { n: int }\nfun f(r: R): bool = r.<caret>check(1)",
            "Create extension function 'R.check'",
            "record R { n: int }\nfun f(r: R): bool = r.check(1)\n\nfun R.check(p1: int): bool = TODO(\"not yet implemented\")",
        )
        fix(
            "record R { n: int }\nfun f(r: R): str = r.<caret>label",
            "Create property 'val R.label'",
            "record R { n: int }\nfun f(r: R): str = r.label\n\nval R.label: str = TODO(\"not yet implemented\")",
        )
        fix(
            "node A(n: int)\nsyn A.x: int = this.<caret>y + 1",
            "Create attribute 'syn A.y'",
            "node A(n: int)\nsyn A.x: int = this.y + 1\n\nsyn A.y: int = TODO(\"not yet implemented\")",
        )
    }

    fun testImplementSyn() {
        fix(
            "node Expr\nnode Num(value: int): Expr\nnode Add(l: Expr, r: Expr): Expr\nsyn Expr.<caret>type: str\nsyn Add.type: str = \"add\"",
            "Implement 'Expr.type' for Num...",
            "node Expr\nnode Num(value: int): Expr\nnode Add(l: Expr, r: Expr): Expr\nsyn Expr.type: str\nsyn Add.type: str = \"add\"\nsyn Num.type: str = TODO(\"not yet implemented\")",
        )
    }

    /** All the attributes the node does not implement, of all its interfaces (the dialog selects all of them). */
    fun testImplementAllMissingAttributes() {
        val nodes = "node Expr\nnode Typed\nnode Num(value: int): Expr\nnode Add(l: Expr, r: Expr): Expr\nfor Num impl Typed\n"
        fix(
            nodes + "syn Expr.<caret>type: str\nsyn Add.type: str = \"add\"\nsyn Expr.size: int\nsyn Add.size: int = 2\n" +
                "syn Typed.names: List<str>",
            "Implement 'Expr.type' for Num...",
            nodes + "syn Expr.type: str\nsyn Add.type: str = \"add\"\nsyn Num.type: str = TODO(\"not yet implemented\")\n" +
                "syn Expr.size: int\nsyn Add.size: int = 2\nsyn Num.size: int = TODO(\"not yet implemented\")\n" +
                "syn Typed.names: List<str>\nsyn Num.names: List<str> = TODO(\"not yet implemented\")",
        )
    }

    /** A node of a module: named as it is written, also in the module. */
    fun testImplementSynOfANodeInAModule() {
        fix(
            "mod m {\n    node Expr\n    node Num(v: int): Expr\n    syn Expr.<caret>type: str\n}",
            "Implement 'm::Expr.type' for m::Num...",
            "mod m {\n    node Expr\n    node Num(v: int): Expr\n    syn Expr.type: str\nsyn m::Num.type: str = TODO(\"not yet implemented\")\n}",
        )
    }
}
