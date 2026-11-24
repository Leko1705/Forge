package com.leko.forge.ide

import ForgeLexer
import com.leko.forge.ide.completion.ForgeCandidateKind
import com.leko.forge.ide.completion.ForgeCompletion
import com.leko.forge.ide.diagnostics.ForgeDiagnostics
import com.leko.forge.ide.diagnostics.ForgeSeverity
import com.leko.forge.ide.diagnostics.ForgeSource
import com.leko.forge.ide.lexer.ForgeEditorLexer
import com.leko.forge.ide.parser.ForgeAntlr
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.ide.project.ForgeProjectKind
import com.leko.forge.ide.project.ForgeProjectOptions
import com.leko.forge.ide.project.ForgeProjectTemplate
import com.leko.forge.ide.project.ForgeTarget
import com.leko.forge.ide.semantics.ForgeSemantics
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor support without an IDE: what a language server builds on. */
class ForgeIdeCoreTest {
    private val root = File(System.getProperty("forge.root") ?: ".")

    private fun lex(text: String): List<Triple<Int, Int, Int>> {
        val lexer = ForgeEditorLexer(text)
        return buildList {
            while (true) {
                add(Triple(lexer.tokenType ?: break, lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
        }
    }

    @Test
    fun theEditorLexerCoversEveryCharacter() {
        val text = "node A(n: int) // a node\n  syn A.x: int = 1 § /* open"
        val tokens = lex(text)
        assertEquals(0, tokens.first().second)
        assertEquals(text.length, tokens.last().third)
        tokens.zipWithNext().forEach { (a, b) -> assertEquals(a.third, b.second) }
        assertTrue(Triple(ForgeEditorLexer.BAD_CHARACTER, text.indexOf('§'), text.indexOf('§') + 1) in tokens)
        assertEquals(ForgeLexer.BLOCK_COMMENT, tokens.last().first, "an unclosed block comment goes to the end")
        assertTrue(ForgeLexer.LINE_COMMENT in tokens.map { it.first })
        assertTrue(ForgeLexer.NODE in ForgeEditorLexer.KEYWORDS)
    }

    @Test
    fun theParserTokensHaveTheOffsetsOfTheText() {
        val text = "node A(n: int)\n\n  node B"
        val tokens = ForgeAntlr.tokens(text)
        val b = tokens.first { it.text == "B" }
        assertEquals(text.indexOf('B'), b.startIndex)
        assertEquals(3, b.line)
        assertEquals(7, b.charPositionInLine)
        assertTrue(ForgeAntlr.parse(text).second.isEmpty())
        assertTrue(ForgeAntlr.parse("node A(").second.isNotEmpty())
    }

    @Test
    fun diagnosticsAreAtTheirSpans() {
        val text = "node A(n: int)\nsyn A.x: int = \"text\"\nsyn A.y: Missing = 0"
        val errors = ForgeDiagnostics.analyze(ForgeSource(text, null), ForgeHost.DISK).filter { it.severity == ForgeSeverity.ERROR }
        assertEquals(setOf("\"text\"", "Missing"), errors.map { text.substring(it.range!!.start, it.range!!.end) }.toSet())
    }

    @Test
    fun theExamplesHaveNoErrors() {
        for (path in listOf("examples/kiln/kiln.forge", "src/test/forge/conformance.forge")) {
            val file = File(root, path)
            val errors = ForgeDiagnostics.analyze(ForgeSource(file.readText(), file), ForgeHost.DISK).filter { it.severity == ForgeSeverity.ERROR }
            assertEquals(emptyList(), errors, path)
        }
    }

    @Test
    fun aMissingImportIsReportedOnItsPath() {
        val dir = createTempDirectory()
        val file = File(dir, "a.forge").apply { writeText("import \"nothere.forge\"\nnode A(n: int)") }
        val problems = ForgeDiagnostics.analyze(ForgeSource(file.readText(), file), ForgeHost.DISK)
        assertEquals(1, problems.size)
        assertEquals("\"nothere.forge\"", file.readText().substring(problems[0].range!!.start, problems[0].range!!.end))
    }

    @Test
    fun namesResolveToTheirDeclarations() {
        val text = "node A(n: int)\nsyn A.x: int = this.n + 1\nfun twice(k: int): int = k * 2"
        val semantics = ForgeSemantics.compute(text, null, ForgeHost.DISK)
        val n = semantics.resolutions.getValue(text.indexOf("n + 1"))
        assertEquals(ForgeNameRole.FIELD, n.role)
        assertEquals(1 to text.indexOf("n:"), n.line to n.column)
        val k = semantics.resolutions.getValue(text.indexOf("k * 2"))
        assertEquals(ForgeNameRole.PARAMETER, k.role)
        assertEquals("int", semantics.declarationTypes[3 to text.substringAfterLast('\n').indexOf("k:")])
        // an incomplete declaration: the compiler can not analyze the file
        assertNull(ForgeSemantics.computeOrNull("$text\nsyn A.y: int = if this.n > 0 then 1", null, ForgeHost.DISK))
    }

    @Test
    fun theHostDecidesWhatCancelsAnAnalysis() {
        class Cancelled : RuntimeException()
        val host = object : ForgeHost {
            override fun read(file: File) = throw Cancelled()
            override fun cancels(e: Throwable) = e is Cancelled
        }
        val file = File(createTempDirectory(), "a.forge").apply { writeText("import \"b.forge\"\nnode A(n: int)") }
        File(file.parentFile, "b.forge").writeText("node B(m: int)")
        val thrown = runCatching { ForgeSemantics.analyze(file.readText(), file, host) { it } }.exceptionOrNull()
        assertTrue(thrown is Cancelled, "$thrown")
    }

    @Test
    fun completesMembersAndKeywords() {
        val text = "node A(n: int)\nsyn A.x: int = this.${ForgeCompletion.DUMMY_PREFIX}"
        val candidates = ForgeCompletion(text, text.length - ForgeCompletion.DUMMY_PREFIX.length, null, ForgeHost.DISK).candidates()
        val n = assertNotNull(candidates.firstOrNull { it.name == "n" }, "$candidates")
        assertEquals(ForgeCandidateKind.FIELD, n.kind)
        val top = "${ForgeCompletion.DUMMY_PREFIX}\nnode A(n: int)"
        assertTrue(ForgeCompletion(top, 0, null, ForgeHost.DISK).candidates().any { it.name == "node" && it.kind == ForgeCandidateKind.KEYWORD })
    }

    @Test
    fun theProjectTemplatesAreCorrectPrograms() {
        for (kind in ForgeProjectKind.entries) for (target in ForgeTarget.entries) {
            val files = ForgeProjectTemplate.textFiles(ForgeProjectOptions("my-lang", kind, target))
            val program = files.getValue("src/main/forge/my_lang.forge")
            val errors = ForgeDiagnostics.analyze(ForgeSource(program, null), ForgeHost.DISK).filter { it.severity == ForgeSeverity.ERROR }
            assertEquals(emptyList(), errors, "$kind $target")
        }
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("forge-ide").toFile().apply { deleteOnExit() }
}
