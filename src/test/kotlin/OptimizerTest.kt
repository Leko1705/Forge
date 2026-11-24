import com.leko.forge.analysis.Analyzer
import com.leko.forge.context.Context
import com.leko.forge.llir.*
import com.leko.forge.parse.parseForge
import com.leko.forge.utils.Options
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** -opt: constant folding and dead code elimination on the LLIR. */
class OptimizerTest {

    private fun optimized(main: File, vararg options: String): LLIRModule {
        val context = Context()
        Options.instance(context).set(arrayOf("-f", main.path, *options))
        val ast = main.parseForge()
        Analyzer.instance(context).analyze(ast)
        return Optimizer.instance(context).optimize(LLIR.instance(context).transform(ast))
    }

    private fun optimized(source: String): LLIRModule {
        val dir = createTempDirectory("forge-opt").toFile()
        try {
            val file = File(dir, "main.forge").apply { writeText(source) }
            return optimized(file)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun LLIRModule.body(name: String): Value = functions.first { it.name == name }.body!!

    @Test
    fun theStandardLibraryIsOnlyGeneratedWhereUsedAlsoWithoutOpt() {
        val dir = createTempDirectory("forge-std").toFile()
        try {
            val file = File(dir, "main.forge").apply { writeText("fun f(): int = [1, 2].sum() + \"a\".upper().size\nfun unused(): int = 1") }
            val context = Context()
            Options.instance(context).set(arrayOf("-f", file.path))
            val ast = file.parseForge()
            Analyzer.instance(context).analyze(ast)
            val m = LLIR.instance(context).transform(ast)
            // ext funs of std are builtins, not functions; of the rest only what is used (sum is a fold), and the program's own
            assertEquals(listOf("sum", "fold", "f", "unused"), m.functions.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun constantsAreFolded() {
        val m = optimized("""
            const base = 4
            const limit: int = base * 3 + 1
            const debug = false
            fun a(): int = limit
            fun b(x: int): int = if debug then x * 1000 else x + limit
            fun c(s: str): str = s + "a" + "b"
            fun d(): bool = 3 in [1, 2, 3] && !(!true) && 1.5 < 2.0
            fun e(): int = [5, 6, 7][1] + [1, 2].size
            fun f(): int = 2147483647 + 1
            fun g(): str? = null ?: "x"
            fun h(): bool = "a" + "b" == "ab"
        """)
        assertEquals(IntValue(13), m.body("a"))
        assertEquals(AddInt(Parameter("x", com.leko.forge.types.IntType), IntValue(13)), m.body("b"))
        assertEquals(StringValue("ab"), (m.body("c") as ConcatStr).rhs)
        assertEquals(BoolValue(true), m.body("d"))
        assertEquals(IntValue(8), m.body("e"))
        assertEquals(IntValue(Int.MIN_VALUE), m.body("f"))
        assertEquals(StringValue("x"), m.body("g"))
        assertEquals(BoolValue(true), m.body("h"))
    }

    @Test
    fun whatFailsAtRunTimeIsNotFolded() {
        val m = optimized("""
            fun a(): int = 1 / 0
            fun b(): int = [1][3]
            fun c(): float = 1.0 / 0.0
            fun d(x: bool): bool = x && false
        """)
        assertTrue(m.body("a") is DivInt)
        assertTrue(m.body("b") is AccessListContainer)
        assertTrue(m.body("c") is DivFloat)
        assertTrue(m.body("d") is AndBool)
    }

    @Test
    fun unusedDeclarationsOfImportedFilesAreRemoved() {
        val dir = createTempDirectory("forge-opt").toFile()
        try {
            File(dir, "lib.forge").writeText("""
                node Expr
                node Num(v: int): Expr
                syn Expr.size: int = 1
                syn Expr.unused: int = helper()
                fun helper(): int = 2
                fun used(): int = 3
                fun alsoUsed(): int = used()
                fun neverUsed(): int = 4
                parser P
                rule P.number: str -> '\d+'
                rule P.unused: str -> 'x'
            """.trimIndent())
            val main = File(dir, "main.forge").apply {
                writeText("import \"lib.forge\"\nfun mine(): int = alsoUsed() + Num(1).size\nfun parse(s: str): str = P.number(s)\nfun dead(): int = if false then neverUsed() else 0")
            }
            val m = optimized(main)
            val names = m.functions.map { it.name }.toSet()
            assertEquals(setOf("mine", "parse", "dead", "alsoUsed", "used"), names)
            val expr = m.nodes.first { it.name == "Expr" }
            assertTrue("size" in expr.synthetics)
            assertFalse("unused" in expr.synthetics)
            assertEquals(setOf("number"), m.parsers.single().rules.keys)

            // -keep keeps declarations the compiled file does not use
            val kept = optimized(main, "-keep", "neverUsed,P.unused,unused", "-opt")
            assertTrue(kept.functions.any { it.name == "neverUsed" } && kept.functions.any { it.name == "helper" })
            assertEquals(setOf("number", "unused"), kept.parsers.single().rules.keys)
            assertTrue("unused" in kept.nodes.first { it.name == "Expr" }.synthetics)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theOptimizedProgramComputesTheSame() {
        assertEquals(forgetest.conformance.report(), forgetest.conformanceOptimized.report())
        val generated = File("build/generated/forge-test/conformanceOptimized/conformance.kt").readText()
        assertFalse("((4 * 3) + 1)" in generated)
    }
}
