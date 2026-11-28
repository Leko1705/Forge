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

    /** On values that are never null: the warnings of the compiler, and their fixes. */
    fun testUnnecessarySafeCallAndElvis() {
        myFixture.configureByText("a.forge", "record R { a: int }\nfun f(r: R): int = r?.a\nfun g(r: R): int = r.a ?: 0")
        val warnings = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.WARNING }
        assertEquals(listOf("?.", "?:"), warnings.map { it.text })
        fix(
            "record R { a: int }\nfun f(r: R): int = r<caret>?.a",
            "Replace '?.' with '.'",
            "record R { a: int }\nfun f(r: R): int = r.a",
        )
        fix(
            "record R { a: int }\nfun g(r: R): int = r.a <caret>?: 0 + 1",
            "Remove the elvis operator",
            "record R { a: int }\nfun g(r: R): int = r.a",
        )
        // where it may be null: nothing
        myFixture.configureByText("a.forge", "record R { a: int }\nfun f(r: R?): int = r?.a ?: 0")
        assertEquals(emptyList<Any>(), myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.WARNING }.map { it.description })
    }

    fun testRemoveUnnecessaryNotNullAndOptional() {
        fix(
            "fun f(x: int?): int = if x != null then x<caret>!! + 1 else 0",
            "Remove '!!'",
            "fun f(x: int?): int = if x != null then x + 1 else 0",
        )
        fix(
            "fun f(x: int): int<caret>? = x + 1",
            "Remove '?'",
            "fun f(x: int): int = x + 1",
        )
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
            "Implement 'syn Expr.type'...",
            "node Expr\nnode Num(value: int): Expr\nnode Add(l: Expr, r: Expr): Expr\nsyn Expr.type: str\nsyn Add.type: str = \"add\"\nsyn Num.type: str = TODO(\"not yet implemented\")",
        )
    }

    /** One fix for the attribute, for all the nodes that do not implement it (the dialog selects all of them). */
    fun testImplementSynForAllNodesThatMissIt() {
        val nodes = "node Expr\nnode Typed\nnode Num(value: int): Expr\nnode Add(l: Expr, r: Expr): Expr\nnode Neg(e: Expr): Expr\n"
        val before = nodes + "syn Expr.<caret>type: str\nsyn Add.type: str = \"add\"\nsyn Expr.size: int = 1"
        myFixture.configureByText("a.forge", before)
        myFixture.doHighlighting()
        assertEquals(listOf("Implement 'syn Expr.type'..."), myFixture.filterAvailableIntentions("Implement").map { it.text })
        fix(
            before,
            "Implement 'syn Expr.type'...",
            nodes + "syn Expr.type: str\nsyn Add.type: str = \"add\"\nsyn Num.type: str = TODO(\"not yet implemented\")\n" +
                "syn Neg.type: str = TODO(\"not yet implemented\")\nsyn Expr.size: int = 1",
        )
    }

    /** A node of a module: named as it is written, also in the module. */
    fun testImplementSynOfANodeInAModule() {
        fix(
            "mod m {\n    node Expr\n    node Num(v: int): Expr\n    syn Expr.<caret>type: str\n}",
            "Implement 'syn m::Expr.type'...",
            "mod m {\n    node Expr\n    node Num(v: int): Expr\n    syn Expr.type: str\nsyn m::Num.type: str = TODO(\"not yet implemented\")\n}",
        )
    }

    private val type = "enum Type { INT, STR, ERROR(msg: str) }\n"
    private val todo = "TODO(\"not yet implemented\")"

    fun testAddRemainingCasesOnTheirLines() {
        fix(
            type + "fun f(t: Type): str = <caret>match t {\n    Type.INT -> \"int\"\n}",
            "Add remaining cases",
            type + "fun f(t: Type): str = match t {\n    Type.INT -> \"int\"\n    Type.STR -> $todo\n    Type.ERROR(msg) -> $todo\n}",
        )
    }

    fun testAddRemainingCasesOnOneLine() {
        fix(
            type + "fun f(t: Type): str = <caret>match t { Type.STR -> \"str\" }",
            "Add remaining cases",
            type + "fun f(t: Type): str = match t { Type.STR -> \"str\"  Type.INT -> $todo  Type.ERROR(msg) -> $todo }",
        )
    }

    fun testAddAllCasesToAnEmptyMatch() {
        fix(
            type + "fun f(t: Type): str =\n    <caret>match t { }",
            "Add remaining cases",
            type + "fun f(t: Type): str =\n    match t {\n        Type.INT -> $todo\n        Type.STR -> $todo\n        Type.ERROR(msg) -> $todo\n    }",
        )
    }

    /** A case with a guard or a pattern in a field does not match all of its item. */
    fun testAddRemainingCasesAfterPartialOnes() {
        fix(
            type + "fun f(t: Type): str = <caret>match t { Type.INT if true -> \"i\"  Type.ERROR(\"x\") -> \"x\" }",
            "Add remaining cases",
            type + "fun f(t: Type): str = match t { Type.INT if true -> \"i\"  Type.ERROR(\"x\") -> \"x\"  Type.INT -> $todo  " +
                "Type.STR -> $todo  Type.ERROR(msg) -> $todo }",
        )
    }

    /** `_ ->` matches the rest already: with all items it could not be reached. */
    fun testNoCasesToAddWithADefault() {
        myFixture.configureByText("a.forge", type + "fun f(t: Type): str = <caret>match t { Type.INT -> \"i\"  _ -> \"?\" }")
        myFixture.doHighlighting()
        assertTrue(myFixture.filterAvailableIntentions("Add remaining cases").isEmpty())
    }

    fun testAddRemainingCasesOfAnEnumInAModule() {
        fix(
            "mod shapes { enum Shape { CIRCLE(r: int), RECT(w: int, h: int) } }\nfun f(s: shapes::Shape): int = <caret>match s { }",
            "Add remaining cases",
            "mod shapes { enum Shape { CIRCLE(r: int), RECT(w: int, h: int) } }\nfun f(s: shapes::Shape): int = match s {\n" +
                "    shapes::Shape.CIRCLE(r) -> $todo\n    shapes::Shape.RECT(w, h) -> $todo\n}",
        )
    }

    fun testNoCasesToAddIfAllAreThere() {
        myFixture.configureByText("a.forge", type + "fun f(t: Type): str = <caret>match t { Type.INT -> \"i\"  Type.STR -> \"s\"  Type.ERROR(m) -> m }")
        myFixture.doHighlighting()
        assertTrue(myFixture.filterAvailableIntentions("Add remaining cases").isEmpty())
    }
}
