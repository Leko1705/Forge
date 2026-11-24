package com.leko.forge.intellij

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.leko.forge.ide.diagnostics.ForgeDiagnostics
import com.leko.forge.ide.diagnostics.ForgeSeverity
import com.leko.forge.ide.diagnostics.ForgeSource
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.ide.semantics.ForgeSemantics
import com.leko.forge.intellij.highlight.ForgeColors
import com.leko.forge.intellij.highlight.ForgeColorSettingsPage
import com.leko.forge.intellij.psi.ForgeDeclaredName
import com.leko.forge.intellij.psi.ForgeNameElement
import com.leko.forge.intellij.semantics.ForgeFileSemantics
import com.leko.forge.intellij.semantics.ForgeImports
import com.leko.forge.parse.STD_SOURCE
import org.antlr.v4.runtime.ANTLRInputStream
import java.awt.Font
import java.io.File

class ForgeNavigationTest : BasePlatformTestCase() {

    companion object {
        val PROGRAM = """
        node Program(decls: List<Expr>)
        node Expr
        node Num(value: int): Expr
        node Add(left: Expr, right: Expr): Expr
        enum Type { INT, ERROR(msg: str) }
        record Diagnostic { msg: str, line: int }
        const limit = 3
        syn Expr.type: Type
        syn Num.type: Type = if this.value > limit then Type.INT else Type.ERROR("small")
        syn Add.type: Type = match this.left.type { Type.INT -> this.right.type  Type.ERROR(m) -> Type.ERROR(m.upper()) }
        val Type.isError: bool = match this { Type.ERROR(_) -> true  _ -> false }
        syn Add.bad: bool = this.left.type.isError || twice(this.depth) > 2
        fun twice(x: int): int = x * 2
        inh depth: int = 0
        inh Add.depth { left <- this.depth + 1  right <- this.depth + 1 }
    """.trimIndent()
    }

    private val program = PROGRAM

    /** The declaration the name at [marker] (the name follows it) resolves to. */
    private fun target(marker: String, text: String = program): PsiElement? {
        val offset = text.indexOf(marker).also { assertTrue("no '$marker'", it >= 0) } + marker.length
        myFixture.configureByText("a.forge", text.substring(0, offset) + "<caret>" + text.substring(offset))
        return myFixture.elementAtCaret
    }

    private fun assertDeclaration(marker: String, expectedName: String, declarationLine: String) {
        val target = target(marker)
        assertTrue("$marker -> $target", target is ForgeDeclaredName)
        assertEquals(expectedName, (target as ForgeDeclaredName).name)
        val line = program.lineSequence().first { declarationLine in it }
        val lineStart = program.indexOf(line)
        assertTrue("$marker -> ${target.textOffset}", target.textOffset in lineStart until lineStart + line.length)
    }

    fun testNavigation() {
        assertDeclaration("if this.", "value", "node Num(value")             // a field of the node
        assertDeclaration("match this.left.", "type", "syn Expr.type: Type") // a syn attribute
        assertDeclaration("this.left.type.", "isError", "val Type.isError") // a val
        assertDeclaration("twice(this.", "depth", "inh depth: int")          // an inh attribute
        assertDeclaration("|| ", "twice", "fun twice")                       // a function
        assertDeclaration("twice(x: int): int = ", "x", "fun twice")                          // a parameter
        assertDeclaration("> ", "limit", "const limit")                      // a constant
        assertDeclaration("node Num(value: int): ", "Expr", "node Expr")     // a type
        assertDeclaration("Type.ERROR(m) -> Type.", "ERROR", "enum Type")    // an enum item
        assertDeclaration("Type.ERROR(m) -> Type.ERROR(", "m", "syn Add.type") // a pattern variable
        assertDeclaration("inh Add.", "depth", "inh depth: int")             // the attribute an inh equation defines
        assertDeclaration("Add.depth { ", "left", "node Add(")                         // the child of an inh equation
        assertDeclaration("syn Add.bad: bool = this.", "left", "node Add(")  // a child
    }

    fun testNavigationIntoTheStandardLibrary() {
        // the standard library is in the jar of the compiler, which tests may not read unless allowed
        val jar = STD_SOURCE.let { ForgeSemantics::class.java.getResource("/forge/$it")!!.path.substringAfter("file:").substringBefore("!") }
        VfsRootAccess.allowRootAccess(testRootDisposable, jar)
        val target = target("(m.")
        assertNotNull(target)
        assertEquals(STD_SOURCE, target!!.containingFile.name)
        assertEquals("upper", (target as ForgeNameElement).name)
    }

    fun testFindUsages() {
        val offset = program.indexOf("syn Expr.") + "syn Expr.".length
        myFixture.configureByText("a.forge", program.substring(0, offset) + "<caret>" + program.substring(offset))
        val declaration = myFixture.elementAtCaret
        assertTrue(declaration is ForgeDeclaredName)
        // this.left.type twice, this.right.type, and the implementations syn Num.type and syn Add.type
        val usages = myFixture.findUsages(declaration)
        assertEquals(usages.map { it.element?.text + "@" + it.navigationOffset }.toString(), 5, usages.size)
        val implementations = usages.map { it.navigationOffset }.filter { program.startsWith("type: Type =", it) }
        assertEquals(2, implementations.size)

        val depth = program.indexOf("inh depth") + "inh ".length
        myFixture.configureByText("a.forge", program.substring(0, depth) + "<caret>" + program.substring(depth))
        // twice(this.depth), the equation, this.depth + 1 twice
        assertEquals(4, myFixture.findUsages(myFixture.elementAtCaret).size)
    }

    fun testRename() {
        val offset = program.indexOf("fun twice") + "fun ".length
        myFixture.configureByText("a.forge", program.substring(0, offset) + "<caret>" + program.substring(offset))
        myFixture.renameElementAtCaret("double")
        myFixture.checkResult(program.replace("twice", "double"))
    }

    private fun highlightAt(marker: String): TextAttributesKey? {
        myFixture.configureByText("a.forge", program)
        val offset = program.indexOf(marker) + marker.length
        return myFixture.doHighlighting()
            .filter { it.startOffset == offset && it.forcedTextAttributesKey != null }
            .map { it.forcedTextAttributesKey }
            .firstOrNull()
    }

    fun testUsesAreColoredByWhatTheyAre() {
        assertEquals(ForgeColors.FIELD, highlightAt("if this."))
        assertEquals(ForgeColors.ATTRIBUTE, highlightAt("match this.left."))
        assertEquals(ForgeColors.PROPERTY, highlightAt("this.left.type."))
        assertEquals(ForgeColors.ATTRIBUTE, highlightAt("twice(this."))
        assertEquals(ForgeColors.FIELD, highlightAt("syn Add.bad: bool = this."))
        assertEquals(ForgeColors.CONSTANT, highlightAt("> "))
        assertEquals(ForgeColors.ATTRIBUTE, highlightAt("syn Expr."))
        assertEquals(ForgeColors.PROPERTY, highlightAt("val Type."))
        assertEquals(ForgeColors.ATTRIBUTE, highlightAt("inh "))
    }

    fun testAttributesAndValsAreItalicFieldsAreNot() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        for (key in listOf(ForgeColors.ATTRIBUTE, ForgeColors.PROPERTY)) {
            assertTrue(key.externalName, scheme.getAttributes(key).fontType and Font.ITALIC != 0)
        }
        assertEquals(0, scheme.getAttributes(ForgeColors.FIELD).fontType and Font.ITALIC)
    }

    fun testResolutionIntoImportedFiles() {
        val dir = kotlin.io.path.createTempDirectory("forge").toFile()
        File(dir, "lib.forge").writeText("node Expr\nsyn Expr.type: int")
        val main = "import \"lib.forge\"\nnode Num(n: int): Expr\nsyn Num.double: int = this.type"
        val semantics = ForgeSemantics.compute(main, File(dir, "main.forge")) { ANTLRInputStream(it.readText()).apply { name = it.absolutePath } }
        val expr = semantics.resolutions.getValue(main.indexOf("Expr"))
        assertEquals(File(dir, "lib.forge").absolutePath, expr.source)
        assertEquals(1, expr.line)
        val type = semantics.resolutions.getValue(main.lastIndexOf("type"))
        assertEquals(ForgeNameRole.ATTRIBUTE, type.role)
        assertEquals(File(dir, "lib.forge").absolutePath, type.source)
        assertEquals(2, type.line)
    }

    fun testIncompleteProgramsAreNotResolved() {
        // while it is typed, a program can not be analyzed: nothing is resolved, and nothing fails
        val file = myFixture.configureByText("a.forge", "node A(n: int)\nsyn A.x: int = this.n +")
        assertTrue(ForgeFileSemantics.of(file).resolutions.isEmpty())
        myFixture.doHighlighting()
    }

    fun testColorSettingsDemoShowsUsesByWhatTheyAre() {
        val demo = ForgeColorSettingsPage().demoText
        assertTrue(demo, "this.<forge_field>left</forge_field>.<forge_attribute>type</forge_attribute>" in demo)
        assertTrue(demo, "<forge_property>isError</forge_property>" in demo)
        // the demo is a correct program, so that the analysis resolves all of it
        val text = demo.replace(Regex("</?forge_[a-z_]+>"), "")
        val errors = ForgeDiagnostics.analyze(ForgeSource(text, null), ForgeImports).filter { it.severity == ForgeSeverity.ERROR }
        assertEquals(emptyList<Any>(), errors)
    }
}

class ForgeRenameTest : com.intellij.testFramework.fixtures.BasePlatformTestCase() {
    private val program = """
        node Expr
        node Num(value: int): Expr
        node Add(left: Expr, right: Expr): Expr
        syn Expr.type: str
        syn Num.type: str = "int"
        syn Add.type: str = if this.left.type == this.right.type then this.left.type else "error"
        syn Add.ok: bool = this.type == "int"
        parser Lang skip '\s+'
        rule Lang.expr: Expr
        rule Lang.expr: Num -> n: '[0-9]+' { Num(n.toInt()) }
        rule Lang.expr: Add -> '\(' l: expr '\+' r: expr '\)' { Add(l, r) }
        rule Lang.program: Expr -> e: expr { e }
    """.trimIndent()

    private fun renameAt(marker: String, newName: String): String {
        val offset = program.indexOf(marker) + marker.length
        myFixture.configureByText("a.forge", program.substring(0, offset) + "<caret>" + program.substring(offset))
        myFixture.renameElementAtCaret(newName)
        return myFixture.editor.document.text
    }

    fun testRenamingADefinitionRenamesItsImplementations() {
        val expected = program.replace(Regex("\\.type\\b"), ".kind")
        assertEquals(expected, renameAt("syn Expr.", "kind"))
        // the program still compiles
        assertEquals(emptyList<Any>(), ForgeDiagnostics.analyze(ForgeSource(expected, null), ForgeImports).filter { it.severity == ForgeSeverity.ERROR })
    }

    fun testRenamingAnImplementationRenamesTheDefinition() {
        assertEquals(program.replace(Regex("\\.type\\b"), ".kind"), renameAt("syn Num.", "kind"))
    }

    fun testRenamingARuleRenamesItsAlternatives() {
        assertEquals(program.replace(Regex("\\bexpr\\b"), "term"), renameAt("rule Lang.", "term"))
    }

    fun testKeywordsAreNoNames() {
        val validator = com.leko.forge.intellij.hierarchy.ForgeNamesValidator()
        assertTrue(validator.isKeyword("node", null))
        assertFalse(validator.isIdentifier("syn", null))
        assertFalse(validator.isIdentifier("2x", null))
        assertTrue(validator.isIdentifier("kind", null))
    }
}

class ForgeLastGoodAnalysisTest : com.intellij.testFramework.fixtures.BasePlatformTestCase() {
    private val program = """
        node Expr
        node Num(value: int): Expr
        syn Expr.type: str
        syn Num.type: str = if this.value > 0 then "pos" else "neg"
        syn Num.double: int = this.value * 2
    """.trimIndent()

    private fun type(offset: Int, text: String) {
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(offset, text)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testNamesStayResolvedWhileADeclarationIsTyped() {
        val file = myFixture.configureByText("a.forge", program)
        assertFalse(com.leko.forge.intellij.semantics.ForgeFileSemantics.of(file).resolutions.isEmpty())
        // lines above, and an incomplete declaration below: the file can not be analyzed now
        type(0, "// the tree\n\n")
        type(myFixture.editor.document.textLength, "\nsyn Num.triple: int = if this.value > 0 then 1")
        val text = myFixture.editor.document.text
        assertNull("the compiler can not analyze it", com.leko.forge.ide.semantics.ForgeSemantics.analyze(text, null, { error("") }) { it })

        // `this.value` in the line of double: still goes to the field, which moved down two lines
        myFixture.editor.caretModel.moveToOffset(text.indexOf("this.value * 2") + "this.".length)
        val target = myFixture.elementAtCaret
        assertEquals(text.indexOf("value: int"), target.textOffset)
        // the gutter icons are still there
        assertTrue(myFixture.findAllGutters().any { it.tooltipText == "Is implemented in syn Num.type" })
        // a changed line has nothing of the old analysis
        val semantics = com.leko.forge.intellij.semantics.ForgeFileSemantics.of(file)
        assertTrue(semantics.resolutions.keys.none { it >= text.indexOf("syn Num.triple") })
    }

    fun testLineMap() {
        val map = com.leko.forge.intellij.semantics.ForgeLineMap.between("a\nb\nc\nd", "x\na\nB\nc\nd\ne")!!
        assertEquals(2, map.line(1))
        assertNull(map.line(2))
        assertEquals(4, map.line(3))
        assertEquals(5, map.line(4))
        assertEquals("x\na\nB\nc\nd\ne".indexOf("c"), map.offset("a\nb\nc\nd".indexOf("c")))
    }
}
