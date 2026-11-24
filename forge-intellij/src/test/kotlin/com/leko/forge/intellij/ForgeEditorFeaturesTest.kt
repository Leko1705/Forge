package com.leko.forge.intellij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.testFramework.EditorTestUtil
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.leko.forge.intellij.editor.ForgeDocumentationProvider
import com.leko.forge.intellij.highlight.ForgeColors

class ForgeEditorFeaturesTest : BasePlatformTestCase() {

    private val program = """
        import "std"
        import "std"

        /* The
           tree. */
        node Expr
        node Num(value: int): Expr
        node Add(left: Expr, right: Expr): Expr

        /**
         * The type of an [Expr], `int` or `str`.
         *
         * Every node has one.
         */
        syn Expr.type: str
        syn Num.type: str = "int"
        syn Add.type: str =
            if this.left.type == this.right.type
            then this.left.type
            else "error"
        inh depth: int = 0
        inh Add.depth {
            left <- this.depth + 1
            right <- this.depth + 1
        }
        circular(0) Expr.size: int = 1
        circular(0) Add.size: int = this.left.size + this.right.size
        record Diagnostic {
            msg: str,
            line: int
        }
        fun twice(x: int): int = match x {
            0 -> 0
            _ -> x * 2
        }
        parser Lang skip '\s+'
        rule Lang.expr: Expr
        rule Lang.expr: Num -> n: '[0-9]+' { Num(n.toInt()) }
    """.trimIndent()

    private fun gutters(): Set<Pair<String, Int>> {
        myFixture.configureByText("a.forge", program)
        return myFixture.findAllGutters().filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>().mapTo(mutableSetOf()) {
            it.tooltipText.toString() to myFixture.editor.document.getLineNumber(it.lineMarkerInfo.startOffset)
        }
    }

    private fun line(text: String) = program.lines().indexOfFirst { text in it }

    fun testGutterIcons() {
        val gutters = gutters()
        fun assertGutter(tooltip: String, lineText: String) =
            assertTrue("$tooltip at '$lineText' in $gutters", (tooltip to line(lineText)) in gutters)
        assertGutter("Is implemented in node Num, node Add", "node Expr")
        assertGutter("Implements node Expr", "node Num(")
        assertGutter("Implements node Expr", "node Add(")
        assertGutter("Is implemented in syn Num.type, syn Add.type", "syn Expr.type")
        assertGutter("Implements syn Expr.type", "syn Num.type")
        assertGutter("Is implemented in inh Add.depth", "inh depth")
        assertGutter("Implements inh depth", "inh Add.depth")
        // a circular attribute with an equation is overridden, not implemented
        assertGutter("Is overridden in circular Add.size", "circular(0) Expr.size")
        assertGutter("Overrides circular Expr.size", "circular(0) Add.size")
        assertGutter("Is implemented in rule Lang.expr: Num", "rule Lang.expr: Expr")
        assertGutter("Implements rule Lang.expr: Expr", "rule Lang.expr: Num")
        assertEquals(gutters.toString(), 12, gutters.size)
    }

    fun testGutterIconsNavigate() {
        myFixture.configureByText("a.forge", program)
        val gutter = myFixture.findAllGutters().first { it.tooltipText == "Is implemented in syn Num.type, syn Add.type" }
        val targets = (gutter as com.intellij.codeInsight.daemon.LineMarkerInfo.LineMarkerGutterIconRenderer<*>)
            .lineMarkerInfo.let { it as com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo<*> }
            .createGotoRelatedItems().map { it.element }
        assertEquals(listOf(line("syn Num.type"), line("syn Add.type")),
            targets.map { myFixture.editor.document.getLineNumber(it!!.textOffset) })
    }

    fun testFolding() {
        myFixture.configureByText("a.forge", program)
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        val regions = myFixture.editor.foldingModel.allFoldRegions.associate {
            program.substring(it.startOffset, it.endOffset).lineSequence().first().trim() to it.placeholderText
        }
        assertEquals("/* ... */", regions["/* The"])
        assertEquals("/** ... */", regions["/**"])
        assertEquals(" ...", regions[""])                              // the body of syn Add.type
        assertEquals("{...}", regions["{"])                            // inh Add.depth, record Diagnostic, match
        assertEquals("...", regions["\"std\""])                        // the imports
        assertEquals(" match x {", regions.entries.firstOrNull { it.value == " ..." && it.key.startsWith("match") }?.let { " match x {" })
        assertEquals(8, myFixture.editor.foldingModel.allFoldRegions.size)
        for (region in myFixture.editor.foldingModel.allFoldRegions) {
            val text = program.substring(region.startOffset, region.endOffset)
            assertTrue("one line: $text", '\n' in text)
        }
    }

    private fun documentation(marker: String): String {
        val offset = program.indexOf(marker) + marker.length
        myFixture.configureByText("a.forge", program.substring(0, offset) + "<caret>" + program.substring(offset))
        val target = myFixture.elementAtCaret
        return ForgeDocumentationProvider().generateDoc(target, myFixture.file.findElementAt(offset)) ?: "null"
    }

    fun testDocumentation() {
        // on a use: the declaration's signature and doc comment
        val type = documentation("if this.left.")
        assertTrue(type, "syn" in type && "Expr" in type && "type" in type)
        assertTrue(type, "The type of an <code>Expr</code>, <code>int</code> or <code>str</code>." in type)
        assertTrue(type, "<p>Every node has one.</p>" in type)
        assertTrue(type, "synthesized attribute" in type)
        assertTrue(type, "Implemented in" in type && "syn Num.type" in type)
        assertFalse(type, "\"int\"" in type)

        val child = documentation("Add.depth {\n    ")
        assertTrue(child, "left: Expr" in child && "child of node Add" in child)

        val parameter = documentation("_ -> ")
        assertTrue(parameter, "x: int" in parameter && "parameter of fun twice(x: int): int" in parameter)

        val local = documentation("Num(n.toInt()) -> ".let { "{ Num(" })
        assertTrue(local, "variable in rule Lang.expr: Num" in local && "str" in local)
    }

    fun testBlockCommentsHaveTheColorOfDocComments() {
        assertEquals(DefaultLanguageHighlighterColors.DOC_COMMENT, ForgeColors.BLOCK_COMMENT.fallbackAttributeKey)
    }
}

class ForgeTypeParameterTest : com.intellij.testFramework.fixtures.BasePlatformTestCase() {
    private val text = "fun <T> List<T>.second(): T? = this[1]\nfun <T> List<T>.first(): T? = this[0]"

    fun testTypeParametersHaveTheColorOfGenerics() {
        assertEquals("TYPE_PARAMETER_NAME_ATTRIBUTES", ForgeColors.TYPE_PARAMETER.fallbackAttributeKey?.externalName)
        // the platform's schemes define it: cyan in the dark ones, teal in the light ones
        val scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
        assertNotNull(scheme.getAttributes(ForgeColors.TYPE_PARAMETER).foregroundColor)
    }

    fun testDeclarationsAndUsesOfTypeParametersAreColored() {
        myFixture.configureByText("a.forge", text)
        val colored = myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey == ForgeColors.TYPE_PARAMETER }
            .map { it.startOffset }
        val expected = Regex("\\bT\\b").findAll(text).map { it.range.first }.toList()
        assertEquals(6, expected.size)
        assertEquals(expected, colored.sorted())
    }

    fun testTypeParametersNavigateToTheirDeclaration() {
        val use = text.indexOf("(): T") + 4
        myFixture.configureByText("a.forge", text.substring(0, use) + "<caret>" + text.substring(use))
        assertEquals(text.indexOf("<T>") + 1, myFixture.elementAtCaret.textOffset)
        // the T of the second function is its own
        val second = text.lastIndexOf("(): T") + 4
        myFixture.configureByText("a.forge", text.substring(0, second) + "<caret>" + text.substring(second))
        assertEquals(text.indexOf("fun <T>", text.indexOf('\n')) + "fun <".length, myFixture.elementAtCaret.textOffset)
    }
}
