package com.leko.forge

import com.leko.forge.context.Context
import com.leko.forge.util.Diagnostic
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import com.leko.forge.utils.Options
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsTest {

    private class Result(val files: List<File>?, val diagnostics: List<Diagnostic>) {
        val errors get() = diagnostics.filter { it.severity == Severity.ERROR }
        val warnings get() = diagnostics.filter { it.severity == Severity.WARNING }
        fun codes(severity: Severity) = diagnostics.filter { it.severity == severity }.map { it.code }
        override fun toString() = diagnostics.joinToString("\n") { "${it.span} ${it.code} ${it.message}" }
    }

    private fun compile(vararg sources: String): Result {
        val dir = Files.createTempDirectory("forge-test").toFile()
        val files = sources.mapIndexed { i, source -> File(dir, "f$i.forge").also { it.writeText(source.trimIndent()) } }
        val context = Context()
        Options.instance(context).set(arrayOf("-d", File(dir, "out").path) + files.map { it.path })
        val generated = ForgeCompiler.instance(context).compile()
        return Result(generated, Log.instance(context).diagnostics.toList())
    }

    private val base = """
        node E
        node Num(v: int): E
        node Add(l: E, r: E): E
        external fun str.int(): int
    """

    @Test
    fun validProgramGeneratesFiles() {
        val result = compile(base + """
            parser P
            rule P.e: E
            rule P.e: Add -> l:e "+" r:e { Add(l, r) } @left(1)
            rule P.e: Num -> v:'[0-9]+' { Num(v.int()) }
        """)
        assertTrue(result.errors.isEmpty(), result.toString())
        assertNotNull(result.files)
        assertEquals(setOf("f0.kt", "ForgeRuntime.kt"), result.files.map { it.name }.toSet())
    }

    @Test
    fun unresolvedConflictIsAWarning() {
        val result = compile(base + """
            parser P
            rule P.e: E
            rule P.e: Add -> l:e "+" r:e { Add(l, r) }
            rule P.e: Num -> v:'[0-9]+' { Num(v.int()) }
        """)
        assertTrue(result.errors.isEmpty(), result.toString())
        assertEquals(listOf("W101"), result.codes(Severity.WARNING), result.toString())
        assertTrue("shift/reduce" in result.warnings.single().message)
        assertNotNull(result.files)
    }

    @Test
    fun unknownRule() {
        val result = compile(base + """
            parser P
            rule P.e: E -> x:nope { x }
        """)
        assertNull(result.files)
        assertTrue(result.errors.any { "nope" in it.message }, result.toString())
    }

    @Test
    fun valuesThatCanNotBeInferred() {
        val result = compile(base + """
            parser P
            rule P.n: Num -> v:'[0-9]+' { Num(v.int()) }
            rule P.pair: E -> n n
            rule P.labeled: E -> x:(n n) { x }
        """)
        assertNull(result.files)
        assertEquals(listOf("E105", "E105"), result.codes(Severity.ERROR), result.toString())
    }

    @Test
    fun duplicateLabels() {
        val result = compile(base + """
            parser P
            rule P.n: Num -> v:'[0-9]+' v:'[a-z]+' { Num(v.int()) }
        """)
        assertEquals(listOf("E101"), result.codes(Severity.ERROR), result.toString())
    }

    @Test
    fun labelTypes() {
        // ? makes labels optional, * makes them lists, labels in only some alternatives are optional
        val result = compile(base + """
            parser P
            rule P.n: Num -> v:'[0-9]+' { Num(v.int()) }
            rule P.a: List<Num> -> ("[" xs:n* "]")? { xs }
            rule P.b: Num -> x:n | "-" y:n { x }
            rule P.c: List<str> -> (w:'[a-z]+' ",")* { w }
            rule P.d: int -> x:n? { x }
        """)
        val messages = result.errors.map { it.message }
        assertEquals(3, result.errors.size, result.toString())
        assertTrue(messages.any { "List<Num>?" in it }, result.toString()) // a: xs is List<Num>?
        assertTrue(messages.any { "Num?" in it && "'Num'" in it }, result.toString()) // b: x is Num?
        assertTrue(messages.any { "'int'" in it && "Num?" in it }, result.toString()) // d
    }

    @Test
    fun producerTypeMismatch() {
        val result = compile(base + """
            parser P
            rule P.n: Num -> v:'[0-9]+' { v }
        """)
        assertEquals(listOf("E009"), result.codes(Severity.ERROR), result.toString())
    }

    @Test
    fun badAnnotations() {
        val result = compile(base + """
            parser P @layout('x')
            rule P.n: Num -> v:'[0-9]+' { Num(v.int()) } @left
            rule P.m: Num -> v:'[0-9]+' { Num(v.int()) } @fancy(3)
        """)
        assertEquals(listOf("E103", "E103", "E103"), result.codes(Severity.ERROR), result.toString())
    }

    @Test
    fun invalidRegexIsTakenLiterally() {
        val result2 = compile(base + """
            parser P
            rule P.n: E -> '(' e:m ')' { e }
            rule P.m: Num -> v:'[0-9]+' { Num(v.int()) }
        """)
        assertTrue(result2.errors.isEmpty(), result2.toString())
        assertEquals(listOf("W103", "W103"), result2.codes(Severity.WARNING))
    }

    @Test
    fun semanticErrors() {
        val result = compile(base + """
            syn E.value: int
            syn Num.value: int = this.v
            fun f(x: int): str = x
            fun g(): int = h(1)
            fun k(e: E): int = e.missing
            inh Add.nothing { l <- 1 }
        """)
        assertNull(result.files)
        val messages = result.errors.joinToString("\n") { it.message }
        assertTrue("Add.value" in messages || "E.value" in messages, result.toString()) // Add lacks an equation
        assertTrue("expected type 'str' but got int" in messages, result.toString())
        assertTrue("'h'" in messages, result.toString())
        assertTrue("missing" in messages, result.toString())
        assertTrue("nothing" in messages, result.toString())
    }

    @Test
    fun syntaxErrorsInForgeFiles() {
        val result = compile("node A(x: int\nnode B")
        assertNull(result.files)
        assertTrue(result.errors.isNotEmpty())
        assertEquals("f0.forge:2:1", result.errors.first().span.toString())
    }

    @Test
    fun parserInheritanceAcrossFiles() {
        val result = compile(
            base + """
            parser P
            rule P.e: E
            rule P.e: Num -> v:'[0-9]+' { Num(v.int()) }
            """,
            """
            parser Q : P
            rule Q.e: Add -> l:e "+" r:e { Add(l, r) } @left(1)
            rule Q.e: Num -> "#" { Num(0) }
            """,
        )
        assertTrue(result.errors.isEmpty(), result.toString())
        assertNotNull(result.files)
        val unknownParent = compile(base + "parser Q : Nope")
        assertTrue(unknownParent.errors.any { "Nope" in it.message }, unknownParent.toString())
    }
}
