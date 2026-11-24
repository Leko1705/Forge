package com.leko.forge.intellij

import ForgeLexer

import ForgeParser
import com.intellij.lang.annotation.HighlightSeverity
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiErrorElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.leko.forge.intellij.annotator.ForgeCompilerAnnotator
import com.leko.forge.intellij.annotator.ForgeSource
import com.leko.forge.intellij.highlight.ForgeColors
import com.leko.forge.intellij.highlight.ForgeColorSettingsPage
import com.leko.forge.intellij.lexer.ForgeLexerAdapter
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeNameRole
import com.leko.forge.intellij.parser.ForgeNameType
import java.io.File

class ForgePluginTest : BasePlatformTestCase() {

    private val root = File(System.getProperty("forge.root") ?: "..")

    /** The .forge files of the repository: examples, test programs and the standard library. */
    private val forgeFiles: List<File> by lazy {
        root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "forge" }
            .toList()
            .also { assertTrue("no .forge files under $root", it.size > 5) }
    }

    private fun tokens(text: String): List<Triple<String, Int, Int>> {
        val lexer = ForgeLexerAdapter()
        lexer.start(text, 0, text.length, 0)
        val tokens = mutableListOf<Triple<String, Int, Int>>()
        while (lexer.tokenType != null) {
            tokens += Triple(lexer.tokenType.toString(), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }

    fun testLexerCoversEveryCharacterOfEveryFile() {
        for (file in forgeFiles) {
            val text = file.readText()
            var position = 0
            for ((type, start, end) in tokens(text)) {
                assertEquals("gap in ${file.name}", position, start)
                assertTrue("empty token in ${file.name}", end > start)
                assertFalse("bad character in ${file.name} at $start: ${text.substring(start, end)}", type == TokenType.BAD_CHARACTER.toString())
                position = end
            }
            assertEquals(text.length, position)
        }
    }

    fun testLexerTokens() {
        val types = tokens("node A(n: int) // c\n/* b */ '\\s+' \"s\" 0xCA !in #").map { it.first }
        assertEquals(
            listOf("NODE", "WS", "IDENT", "LPAREN", "IDENT", "COLON", "WS", "IDENT", "RPAREN", "WS", "LINE_COMMENT", "WS",
                "BLOCK_COMMENT", "WS", "REGEX_STRING", "WS", "STRING", "WS", "HEX", "WS", "NOTIN", "WS", "BAD_CHARACTER"),
            types,
        )
        // a block comment that is not closed yet goes to the end
        assertEquals(listOf("IDENT", "WS", "BLOCK_COMMENT"), tokens("a /* b\nc").map { it.first })
    }

    fun testLexerRestartsAtAnyToken() {
        val text = File(root, "examples/kiln/kiln.forge").readText()
        val all = tokens(text)
        for ((_, start, _) in all.filter { it.second % 7 == 0 }) {
            val lexer = ForgeLexerAdapter()
            lexer.start(text, start, text.length, 0)
            val rest = mutableListOf<Triple<String, Int, Int>>()
            while (lexer.tokenType != null) { rest += Triple(lexer.tokenType.toString(), lexer.tokenStart, lexer.tokenEnd); lexer.advance() }
            assertEquals(all.dropWhile { it.second < start }, rest)
        }
    }

    /** The syntax errors of the compiler: its lexer and parser, as `forge -f` runs them. */
    private fun compilerSyntaxErrors(text: String): List<String> {
        val errors = mutableListOf<String>()
        val listener = object : BaseErrorListener() {
            override fun syntaxError(r: Recognizer<*, *>?, o: Any?, line: Int, col: Int, msg: String, e: RecognitionException?) {
                errors += "$line:$col $msg"
            }
        }
        val lexer = ForgeLexer(CharStreams.fromString(text)).apply { removeErrorListeners(); addErrorListener(listener) }
        ForgeParser(CommonTokenStream(lexer)).apply { removeErrorListeners(); addErrorListener(listener) }.root()
        return errors
    }

    /** Every file of the repository has syntax errors in the editor exactly if the compiler finds some. */
    fun testSyntaxErrorsAreThoseOfTheCompiler() {
        var withErrors = 0
        for (file in forgeFiles) {
            val text = file.readText()
            val psi = myFixture.configureByText("check.forge", text)
            assertEquals(text, psi.text)
            val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java).map { it.errorDescription }
            val expected = compilerSyntaxErrors(text)
            assertEquals("${file.path}: $errors / $expected", expected.isEmpty(), errors.isEmpty())
            if (expected.isNotEmpty()) withErrors++
        }
        // the repository has a few files of an older syntax (cfg.forge)
        assertTrue(withErrors < forgeFiles.size / 2)
    }

    fun testSyntaxErrorsAreReported() {
        val psi = myFixture.configureByText("a.forge", "node A(n: int\nsyn A.x: int = ")
        val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
        assertTrue(errors.isNotEmpty())
        assertEquals("node A(n: int\nsyn A.x: int = ", psi.text)
    }

    /** ANTLR's error recovery makes up missing tokens (here the `:`), from the source of the tokens. */
    fun testRecoveryWithMissingTokens() {
        for (text in listOf("node A(n int)", "syn A.x int = 1", "fun f(: int = 2", "record R { a: int b: str }")) {
            val psi = myFixture.configureByText("a.forge", text)
            assertTrue(text, PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java).isNotEmpty())
            assertEquals(text, psi.text)
            myFixture.doHighlighting()
        }
    }

    fun testNamesHaveTheirRoles() {
        val psi = myFixture.configureByText("a.forge", """
            node Expr
            node Num(value: int): Expr
            syn Num.double: int = twice(this.value)
            fun twice(x: int): int = x * 2
            enum Color { RED }
            op fun Color.plus(o: Color): Color = Color.RED
        """.trimIndent())
        val roles = PsiTreeUtil.collectElements(psi) { it.node.elementType is ForgeNameType }
            .map { it.text to (it.node.elementType as ForgeNameType).role }
        assertEquals(listOf(
            "Expr" to ForgeNameRole.NODE_DECLARATION,
            "Num" to ForgeNameRole.NODE_DECLARATION, "value" to ForgeNameRole.PARAMETER, "int" to ForgeNameRole.BUILTIN_TYPE,
            "Expr" to ForgeNameRole.TYPE_REFERENCE,
            "Num" to ForgeNameRole.TYPE_REFERENCE, "double" to ForgeNameRole.ATTRIBUTE, "int" to ForgeNameRole.BUILTIN_TYPE,
            "twice" to ForgeNameRole.FUNCTION_CALL, "value" to ForgeNameRole.FIELD,
            "twice" to ForgeNameRole.FUNCTION_DECLARATION, "x" to ForgeNameRole.PARAMETER, "int" to ForgeNameRole.BUILTIN_TYPE,
            "int" to ForgeNameRole.BUILTIN_TYPE, "x" to ForgeNameRole.IDENTIFIER,
            "Color" to ForgeNameRole.TYPE_DECLARATION, "RED" to ForgeNameRole.ENUM_CONSTANT,
            "op" to ForgeNameRole.SOFT_KEYWORD, "Color" to ForgeNameRole.TYPE_REFERENCE, "plus" to ForgeNameRole.FUNCTION_DECLARATION,
            // `Color.RED` in an expression: what `Color` is only the analysis knows
            "o" to ForgeNameRole.PARAMETER, "Color" to ForgeNameRole.TYPE_REFERENCE, "Color" to ForgeNameRole.TYPE_REFERENCE,
            "Color" to ForgeNameRole.IDENTIFIER, "RED" to ForgeNameRole.FIELD,
        ), roles)
    }

    fun testCompilerErrorsAreAtTheirSpans() {
        val text = "node A(n: int)\nsyn A.x: int = \"text\"\nsyn A.y: Missing = 0"
        val problems = ForgeCompilerAnnotator.analyze(ForgeSource(text, null))
        val errors = problems.filter { it.severity == HighlightSeverity.ERROR }
        assertTrue(problems.toString(), errors.size >= 2)
        for (error in errors) assertNotNull(error.toString(), error.range)
        val covered = errors.map { text.substring(it.range!!.startOffset, it.range.endOffset) }
        assertEquals(setOf("\"text\"", "Missing"), covered.toSet())
    }

    fun testCorrectProgramsHaveNoCompilerErrors() {
        // the imports are read through the IDE's file system
        VfsRootAccess.allowRootAccess(testRootDisposable, root.absolutePath)
        for (path in listOf("examples/kiln/kiln.forge", "src/test/forge/conformance.forge", "src/test/forge/flow.forge")) {
            val file = File(root, path)
            val problems = ForgeCompilerAnnotator.analyze(ForgeSource(file.readText(), file))
            assertEquals(path, emptyList<Any>(), problems.filter { it.severity == HighlightSeverity.ERROR })
        }
    }

    fun testMissingImport() {
        val dir = createTempDir()
        val file = File(dir, "a.forge").apply { writeText("import \"nothere.forge\"\nnode A(n: int)") }
        val problems = ForgeCompilerAnnotator.analyze(ForgeSource(file.readText(), file))
        assertEquals(1, problems.size)
        assertEquals("\"nothere.forge\"", file.readText().substring(problems[0].range!!.startOffset, problems[0].range!!.endOffset))
    }

    fun testErrorsInTheEditor() {
        myFixture.configureByText("a.forge", "node A(n: int)\nsyn A.x: int = <error descr=\"expected type 'int' but got str [E009]\">\"text\"</error>\n" +
            "syn A.y: <error descr=\"can not find 'Missing' [E008]\">Missing</error> = 0")
        myFixture.checkHighlighting(false, false, false)
    }

    fun testColorSettingsDemoParsesAndTagsNames() {
        val page = ForgeColorSettingsPage()
        val demo = page.demoText
        val plain = demo.replace(Regex("</?forge_[a-z_]+>"), "")
        val psi = myFixture.configureByText("demo.forge", plain)
        val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
        assertTrue(errors.map { it.errorDescription }.toString(), errors.isEmpty())
        assertTrue("<forge_node_declaration>Program</forge_node_declaration>" in demo)
        assertEquals(ForgeNameRole.entries.size, page.additionalHighlightingTagToDescriptorMap.size)
        assertNotNull(ForgeColors.of(ForgeNameRole.ATTRIBUTE))
        assertTrue(ForgeTokenTypes.of(ForgeLexer.NODE) in ForgeTokenTypes.KEYWORDS)
        assertFalse(ForgeTokenTypes.of(ForgeLexer.IDENT) in ForgeTokenTypes.KEYWORDS)
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("forge").toFile()
}
