package com.leko.forge.intellij

import ForgeLexer

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
        // and the run icon on the first declaration
        assertGutter("Compile a.forge", "import \"std\"")
        assertEquals(gutters.toString(), 13, gutters.size)
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

    fun testTraitImplementationsHaveGutterIcons() {
        val text = """
            trait Named { fun name(): str }
            record Dog { n: str }
            fun Dog.name(): str = this.n
            record Cat { n: str }
            fun Cat.other(): str = this.n
        """.trimIndent()
        myFixture.configureByText("t.forge", text)
        val gutters = myFixture.findAllGutters().filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
            .filter { it.icon === ForgeIcons.TRAIT_IMPLEMENTATION }
        assertEquals(2, gutters.size)
        // on the member of the trait: its implementations
        val member = gutters.single { myFixture.editor.document.getLineNumber(it.lineMarkerInfo.startOffset) == 0 }
        assertTrue(member.tooltipText.toString(), "Is implemented by<br><b>fun Dog.name</b>" in member.tooltipText.toString())
        val implementations = (member.lineMarkerInfo as com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo<*>).createGotoRelatedItems().map { it.element }
        assertEquals(listOf(2), implementations.map { myFixture.editor.document.getLineNumber(it!!.textOffset) })
        val gutter = gutters.single { it !== member }
        assertEquals(2, myFixture.editor.document.getLineNumber(gutter.lineMarkerInfo.startOffset))
        assertTrue(gutter.tooltipText.toString(), "Implements <b>Named.name</b>: Dog is Named" in gutter.tooltipText.toString())
        // it leads to the member of the trait
        val targets = (gutter.lineMarkerInfo as com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo<*>).createGotoRelatedItems().map { it.element }
        assertEquals(listOf(0), targets.map { myFixture.editor.document.getLineNumber(it!!.textOffset) })
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
        // [Expr] links to its declaration
        assertTrue(type, "The type of an <a href=\"psi_element://Expr\"><code>Expr</code></a>, <code>int</code> or <code>str</code>." in type)
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

    fun testExpressionsInStringsAreCode() {
        val text = "fun f(n: int): str = \"a ${'$'}{n + 1} b ${'$'}n\""
        myFixture.configureByText("s.forge", text)
        // the lexer: text, `${`, the expression's tokens, `}`, text, `$`, a name
        val lexer = com.leko.forge.intellij.lexer.ForgeLexerAdapter()
        lexer.start(text, 0, text.length, 0)
        val tokens = buildList {
            while (lexer.tokenType != null) {
                add(lexer.tokenType.toString() to text.substring(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
        }
        val string = tokens.dropWhile { it.second != "\"a " }
        assertEquals(listOf("STRING" to "\"a ", "INTERPOLATION_START" to "${'$'}{", "IDENT" to "n", "WS" to " ", "PLUS" to "+", "WS" to " ",
            "INT" to "1", "INTERPOLATION_END" to "}", "STRING" to " b ", "INTERPOLATION_START" to "${'$'}", "IDENT" to "n", "STRING" to "\""), string)
        // `${` has the color of keywords
        assertEquals(DefaultLanguageHighlighterColors.KEYWORD,
            com.leko.forge.intellij.highlight.ForgeSyntaxHighlighter().getTokenHighlights(com.leko.forge.intellij.lexer.ForgeTokenTypes.INTERPOLATION_START)[0].fallbackAttributeKey)
        // a name in a string is a name: it leads to its declaration
        val inString = text.indexOf("n + 1")
        val target = myFixture.file.findReferenceAt(inString)?.resolve()
        assertNotNull(target)
        assertEquals(text.indexOf("n: int"), target!!.textOffset)
    }

    fun testQuotesAfterAnExpressionInAString() {
        // typing `"` at the end of a string with an expression closes it instead of opening another
        myFixture.configureByText("q.forge", "fun f(n: int): str = \"a ${'$'}{n}<caret>\"")
        myFixture.type("\"")
        myFixture.checkResult("fun f(n: int): str = \"a ${'$'}{n}\"<caret>")
        // a new string gets its closing quote
        myFixture.configureByText("r.forge", "fun f(): str = <caret>")
        myFixture.type("\"")
        myFixture.checkResult("fun f(): str = \"<caret>\"")
    }

    fun testTodoIsYellowNotAWarning() {
        myFixture.configureByText("t.forge", "fun f(n: int): int = match n { _ -> TODO(\"later\") }")
        val infos = myFixture.doHighlighting()
        assertTrue(infos.toString(), infos.none { it.severity == com.intellij.lang.annotation.HighlightSeverity.WARNING })
        // the whole call in the color of TODO comments
        val todo = infos.single { it.forcedTextAttributesKey == com.leko.forge.intellij.highlight.ForgeColors.TODO_CALL }
        assertEquals("TODO(\"later\")", todo.text)
        assertEquals(com.intellij.openapi.editor.colors.CodeInsightColors.TODO_DEFAULT_ATTRIBUTES, todo.forcedTextAttributesKey.fallbackAttributeKey)
        // `_` is plain text
        assertEquals(0, com.leko.forge.intellij.highlight.ForgeSyntaxHighlighter()
            .getTokenHighlights(com.leko.forge.intellij.lexer.ForgeTokenTypes.of(ForgeLexer.UNDERSCORE)).size)
    }

    fun testDocCommentsAreRendered() {
        val text = """
            /**
             * Doubles [n], **always**:
             *
             * - for ints
             * - also for *negative* ones
             *
             * ```
             * twice(2) == 4
             * ```
             */
            fun twice(n: int): int = n * 2
            /* not a doc comment */
            fun once(n: int): int = n
        """.trimIndent()
        myFixture.configureByText("d.forge", text)
        val provider = ForgeDocumentationProvider()
        val comments = mutableListOf<com.intellij.psi.PsiDocCommentBase>()
        provider.collectDocComments(myFixture.file) { comments += it }
        // only /** */ are doc comments; each has the declaration after it as its owner
        assertEquals(1, comments.size)
        assertEquals("twice", (comments[0].owner as com.leko.forge.intellij.psi.ForgeDeclaredName).name)
        val html = provider.generateRenderedDoc(comments[0])!!
        assertTrue(html, "<b>always</b>" in html)
        assertTrue(html, "<ul><li>for ints</li><li>also for <i>negative</i> ones</li></ul>" in html)
        // a parameter: a link to it; the code block highlighted
        assertTrue(html, "href=\"psi_element://n\"" in html)
        assertTrue(html, "<pre><code>" in html && "twice" in html.substringAfter("<pre><code>"))
        assertNotNull(provider.getDocumentationElementForLink(psiManager, "n", comments[0]))
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
