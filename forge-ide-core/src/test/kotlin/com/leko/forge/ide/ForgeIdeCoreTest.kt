package com.leko.forge.ide

import ForgeLexer
import ForgeParser
import com.leko.forge.ide.completion.ForgeCandidateKind
import com.leko.forge.ide.completion.ForgeCompletion
import com.leko.forge.ide.diagnostics.ForgeDiagnostics
import com.leko.forge.ide.diagnostics.ForgeSeverity
import com.leko.forge.ide.diagnostics.ForgeSource
import com.leko.forge.ide.lexer.ForgeEditorLexer
import com.leko.forge.ide.outline.ForgeDeclarations
import com.leko.forge.ide.parser.ForgeAntlr
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.ide.project.ForgeProjectKind
import com.leko.forge.ide.project.ForgeProjectOptions
import com.leko.forge.ide.project.ForgeProjectTemplate
import com.leko.forge.ide.project.ForgeTarget
import com.leko.forge.ide.semantics.ForgeLineShift
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
    fun theEditorLexerSplitsStringsWithExpressions() {
        val text = "\"a ${'$'}{f(1)} ${'$'}n \\${'$'}x\" \"plain\""
        val tokens = mutableListOf<Pair<Int, String>>()
        ForgeEditorLexer(text, 0, text.length, interpolations = true).apply {
            while (tokenType != null) { tokens += tokenType!! to text.substring(tokenStart, tokenEnd); advance() }
        }
        assertEquals(listOf(
            ForgeLexer.STRING to "\"a ", ForgeEditorLexer.INTERPOLATION_START to "${'$'}{", ForgeLexer.IDENT to "f",
            ForgeLexer.LPAREN to "(", ForgeLexer.INT to "1", ForgeLexer.RPAREN to ")", ForgeEditorLexer.INTERPOLATION_END to "}",
            ForgeLexer.STRING to " ", ForgeEditorLexer.INTERPOLATION_START to "${'$'}", ForgeLexer.IDENT to "n",
            ForgeLexer.STRING to " \\${'$'}x\"", ForgeLexer.WS to " ", ForgeLexer.STRING to "\"plain\"",
        ), tokens)
        // without splitting, a string is one token
        assertEquals(3, lex(text).size)
    }

    @Test
    fun todoCallsAreColoredNotWarnings() {
        val text = "fun f(n: int): int = if n > 0 then n else TODO(\"negative\")"
        val problems = ForgeDiagnostics.analyze(ForgeSource(text, null), ForgeHost.DISK)
        assertTrue(problems.none { it.severity == ForgeSeverity.WARNING || it.severity == ForgeSeverity.ERROR }, problems.toString())
        val todo = problems.single { it.severity == ForgeSeverity.TODO }
        assertEquals("TODO: negative", todo.message)
        assertEquals("TODO(\"negative\")", text.substring(todo.range!!.start, todo.range!!.end))
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
    fun usePathsResolveToModules() {
        val text = "mod geometry {\n  record Point { x: int }\n  mod shapes { const unit = 1 }\n  mod inner {\n    use shapes\n  }\n}\n" +
            "use geometry\nuse geometry::shapes\nuse geometry::Point\nfun f(): int = shapes::unit"
        val semantics = ForgeSemantics.compute(text, null, ForgeHost.DISK)
        fun target(of: String) = semantics.resolutions.getValue(text.indexOf(of)).let { Triple(it.name, it.line, it.column) }
        assertEquals(Triple("geometry", 1, 4), target("geometry\nuse"))
        assertEquals(Triple("shapes", 3, 6), target("geometry::shapes"))
        assertEquals(Triple("Point", 2, 9), target("geometry::Point"))
        assertEquals(ForgeNameRole.MODULE, semantics.resolutions.getValue(text.indexOf("geometry\nuse")).role)
        // in a module: also the modules around it
        assertEquals(Triple("shapes", 3, 6), target("shapes\n  }"))
    }

    @Test
    fun traitParametersResolveToTheTrait() {
        val text = "trait Named { fun name(): str }\nfun hello(x: Named): str = x.name()"
        val semantics = ForgeSemantics.compute(text, null, ForgeHost.DISK)
        val named = semantics.resolutions.getValue(text.lastIndexOf("Named"))
        assertEquals(ForgeNameRole.TRAIT, named.role)
        assertEquals(1 to 6, named.line to named.column)
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

    @Test
    fun theOutlineHasTheDeclarationsWithTheirMembers() {
        val text = "/** The types. */\nenum Type { INT, ERROR(msg: str) }\nnode Add(left: Expr, right: Expr): Expr\nnode Expr\n" +
            "syn Add.type: Type = this.left.type\nconst limit = 3"
        val declarations = ForgeDeclarations(text, ForgeAntlr.parse(text).first)
        val outline = declarations.outline()
        assertEquals(listOf("enum Type { INT, ERROR(msg: str) }", "node Add(left: Expr, right: Expr): Expr", "node Expr", "syn Add.type: Type", "const limit"),
            outline.map { it.label })
        assertEquals(listOf("INT", "ERROR(msg: str)"), outline[0].children.map { it.label })
        assertTrue(outline[0].isEnum)
        assertEquals(listOf("left: Expr", "right: Expr"), outline[1].children.map { it.label })
        assertTrue(outline[2].isInterface)
        val type = declarations.info(outline[0].name)!!
        assertEquals("enum", type.kind)
        assertEquals("/** The types. */", type.comment)
        val left = declarations.nameAt(text.indexOf("left"))!!
        assertEquals("child of node Add(left: Expr, right: Expr): Expr", declarations.info(left)!!.kind)
        assertEquals("const limit = 3", declarations.info(outline[4].name)!!.signature)
    }

    @Test
    fun theLanguageFeaturesAreAnalyzedAsTheCompilerDoes() {
        val file = File(root, "src/test/forge/language.forge")
        val errors = ForgeDiagnostics.analyze(ForgeSource(file.readText(), file), ForgeHost.DISK).filter { it.severity == ForgeSeverity.ERROR }
        assertEquals(emptyList(), errors)
        // mod, use and trait are keywords before a name, names otherwise
        assertTrue(ForgeLexer.MOD in lex("mod m {}").map { it.first })
        assertTrue(ForgeLexer.MOD !in lex("fun mod(x: int): int = x").map { it.first })
        // an expression in a string is parsed with the file: its errors are syntax errors
        assertTrue(ForgeAntlr.parse("fun f(): str = \"${'$'}{1 +}\"").second.isNotEmpty())
        assertTrue(ForgeAntlr.parse("fun f(): str = \"${'$'}{1 + 2}\"").second.isEmpty())
    }

    @Test
    fun namesOfTheLanguageFeaturesResolve() {
        val text = "mod geo {\n    record Point { x: int }\n}\nfun f(g: (int) -> int): int = g(y) + geo::Point(1).x where y = 2\n" +
            "fun h(): int = [1].map({ v -> v + 1 })[0]\nfun s(n: int): str = \"${'$'}{n}\""
        val semantics = ForgeSemantics.compute(text, null, ForgeHost.DISK)
        // a call of a function value: the parameter
        assertEquals(ForgeNameRole.PARAMETER, semantics.resolutions.getValue(text.indexOf("g(y)")).role)
        // a where binding
        val y = semantics.resolutions.getValue(text.indexOf("y) +"))
        assertEquals(ForgeNameRole.LOCAL_VARIABLE, y.role)
        assertEquals(4 to text.substringAfter("}\n}\n").indexOf("y = 2"), y.line to y.column)
        // a qualified name: the declaration in the module, by the name written
        val point = semantics.resolutions.getValue(text.indexOf("geo::Point(1)"))
        assertEquals("geo::Point", point.name)
        assertEquals(2, point.line)
        // a lambda parameter, and a name in a string
        assertEquals(ForgeNameRole.LOCAL_VARIABLE, semantics.resolutions.getValue(text.indexOf("v + 1")).role)
        assertEquals(ForgeNameRole.PARAMETER, semantics.resolutions.getValue(text.lastIndexOf("n}")).role)
    }

    @Test
    fun methodsThatImplementTraitsAreKnown() {
        val text = "trait Named { fun name(): str\n    val size: int }\ntrait Ord { op fun compareTo(other: Self): int }\n" +
            "record Dog { n: str }\nfun Dog.name(): str = this.n\nval Dog.size: int = 1\n" +
            "record Cat { n: str }\nfun Cat.name(): str = this.n\n" +
            "record Box<T> { item: T }\nfun <T> Box<T>.name(): str = \"box\"\nval <T> Box<T>.size: int = 1\n" +
            "fun Dog.compareTo(other: Dog): int = 0"
        val implementations = ForgeSemantics.compute(text, null, ForgeHost.DISK).traitImplementations
        val dogName = implementations.getValue(text.indexOf("name(): str = this.n"))
        assertEquals(listOf("Named" to "Dog"), dogName.map { it.trait to it.type })
        // the member it implements, where it is declared
        assertEquals(1 to text.indexOf("name()"), dogName[0].member.line to dogName[0].member.column)
        assertEquals(listOf("Named"), implementations.getValue(text.indexOf("size: int = 1")).map { it.trait })
        // Cat has no size: it does not have the trait; a plain fun compareTo is no operator of Ord
        assertNull(implementations[text.lastIndexOf("name(): str = this.n")])
        assertNull(implementations[text.indexOf("compareTo(other: Dog)")])
        // a generic record has it through generic methods
        assertEquals(listOf("Box<T>"), implementations.getValue(text.indexOf("name(): str = \"box\"")).map { it.type })
        // and the other way: the members of the trait know their implementations
        val implementers = ForgeSemantics.compute(text, null, ForgeHost.DISK).traitImplementers
        val name = implementers.getValue(text.indexOf("name(): str\n"))
        assertEquals(listOf("fun Dog.name", "fun Box<T>.name"), name.map { it.label })
        assertEquals(5, name[0].declaration.line)
        assertEquals(listOf("val Dog.size", "val Box<T>.size"), implementers.getValue(text.indexOf("size: int }")).map { it.label })
        // a val member: also an attribute or a field
        val nodes = "trait Sized { val size: int }\nnode Expr\nnode Num(size: int): Expr\nnode Neg(e: Expr): Expr\nsyn Neg.size: int = 1\nrecord R { size: int }"
        val sized = ForgeSemantics.compute(nodes, null, ForgeHost.DISK).traitImplementers.getValue(nodes.indexOf("size: int }"))
        // (and the size of the builtin types, from the standard library)
        assertEquals(setOf("field Num.size", "attribute Neg.size", "field R.size", "val str.size", "val List<T>.size", "val Set<T>.size", "val Map<K, V>.size"),
            sized.map { it.label }.toSet())
    }

    @Test
    fun missingDeclarationsCanBeCreated() {
        fun fixes(text: String): List<String> = ForgeDiagnostics.analyze(ForgeSource(text, null), ForgeHost.DISK)
            .flatMap { p -> p.fixes.map { text.substring(0, it.offset) + it.text + text.substring(it.offset) } }
        val todo = "TODO(\"not yet implemented\")"
        // a function, with the types of the arguments and what is expected of the call
        assertEquals(listOf("fun f(x: int): str = g(x, \"a\")\n\nfun g(x: int, p2: str): str = $todo"),
            fixes("fun f(x: int): str = g(x, \"a\")"))
        // an extension function of the receiver
        assertEquals(listOf("record R { n: int }\nfun f(r: R): bool = r.check(1)\n\nfun R.check(p1: int): bool = $todo"),
            fixes("record R { n: int }\nfun f(r: R): bool = r.check(1)"))
        // a val, of a node a syn attribute
        assertEquals(listOf("fun f(xs: List<int>): int = xs.total\n\nval List<int>.total: int = $todo"), fixes("fun f(xs: List<int>): int = xs.total"))
        assertEquals(listOf("node A(n: int)\nsyn A.x: int = this.y\n\nsyn A.y: int = $todo"), fixes("node A(n: int)\nsyn A.x: int = this.y"))
        // what the expression around expects
        assertTrue(fixes("fun f(): int = 1 + h()").single().endsWith("fun h(): int = $todo"))
        // in a module: after the declaration, in the module
        assertEquals(listOf("mod m {\n    fun f(): int = g()\n\n    fun g(): int = $todo\n}"), fixes("mod m {\n    fun f(): int = g()\n}"))
    }

    @Test
    fun theOutlineHasModulesAndTraits() {
        val text = "mod geo {\n    record Point { x: int }\n    mod deep { const c = 1 }\n}\ntrait Named { fun name(): str }"
        val declarations = ForgeDeclarations(text, ForgeAntlr.parse(text).first)
        val outline = declarations.outline()
        assertEquals(listOf("mod geo", "trait Named"), outline.map { it.label })
        assertEquals(listOf("record Point { x: int }", "mod deep"), outline[0].children.map { it.label })
        assertEquals(listOf("const c"), outline[0].children[1].children.map { it.label })
        assertEquals(listOf("fun name(): str"), outline[1].children.map { it.label })
        assertEquals("module", declarations.info(outline[0].name)!!.kind)
        assertEquals("trait", declarations.info(outline[1].name)!!.kind)
        // Self in a trait is a keyword
        val self = "trait Ord { op fun compareTo(other: Self): int }"
        val tree = ForgeAntlr.parse(self).first
        val selfToken = ForgeAntlr.tokens(self).first { it.text == "Self" }
        fun find(t: org.antlr.v4.runtime.tree.ParseTree): ForgeParser.NamedTypeContext? =
            (t as? ForgeParser.NamedTypeContext)?.takeIf { it.name.startIndex == selfToken.startIndex }
                ?: (0 until t.childCount).firstNotNullOfOrNull { find(t.getChild(it)) }
        val type = find(tree)!!
        assertEquals(ForgeNameRole.SOFT_KEYWORD, com.leko.forge.ide.parser.nameRole(type, type.name))
    }

    @Test
    fun linesShiftAroundAnEdit() {
        val map = ForgeLineShift.between("a\nb\nc\nd", "a\nB\nx\nc\nd")
        assertEquals(1, map.line(1))
        assertNull(map.line(2))
        assertEquals(4, map.line(3))
        assertEquals(5, map.line(4))
        // "c" moved from offset 4 to 6
        assertEquals(6, map.offset(4))
        assertNull(map.offset(2))
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("forge-ide").toFile().apply { deleteOnExit() }
}
