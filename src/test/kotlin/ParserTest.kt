import forgetest.parser.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParserTest {

    /** Expressions in prefix notation, to compare trees. */
    private fun show(e: Expr): String = when (e) {
        is Num -> e.value.toString()
        is Var -> e.name
        is Bin -> "(${e.op} ${show(e.l)} ${show(e.r)})"
        is Neg -> "(neg ${show(e.e)})"
        is Fact -> "(! ${show(e.e)})"
        is Cond -> "(? ${show(e.c)} ${show(e.a)} ${show(e.b)})"
        is Call -> "${e.name}(${e.args.joinToString(", ") { show(it) }})"
        else -> error(e)
    }

    private fun expr(source: String) = show(Calc.expr(source))

    @Test
    fun operatorsHavePrecedenceAndAssociativity() {
        assertEquals("(+ 1 (* 2 3))", expr("1 + 2 * 3"))
        assertEquals("(- (- 1 2) 3)", expr("1 - 2 - 3"))
        assertEquals("(^ 2 (^ 3 4))", expr("2 ^ 3 ^ 4"))
        assertEquals("(* (neg a) b)", expr("-a * b"))
        assertEquals("(neg (! a))", expr("-a!"))
        assertEquals("(* (+ 1 2) 3)", expr("(1 + 2) * 3"))
        assertEquals("(? a (+ b 1) (? c d e))", expr("a ? b + 1 : c ? d : e"))
        assertEquals("f(1, (+ x 2), g())", expr("f(1, x + 2, g())"))
    }

    @Test
    fun statementsKeywordsAndComments() {
        val program = Calc.program(
            """
            let x = 10 // the counter
            while x do
              print x
              let x = x - 1
            end
            """.trimIndent()
        )
        assertEquals(2, program.stmts.size)
        val loop = program.stmts[1] as While
        assertEquals("x", show(loop.cond))
        assertEquals(listOf("x", "(- x 1)"), loop.body.map { show((it as? Print)?.value ?: (it as Let).value) })
        assertSame(program, (loop.body[0] as __Node).__parent?.__parent)
        assertEquals(3, countStatements("print 1 print 2 let y = 3"))
        // keywords are no identifiers
        assertFailsWith<ParseException> { Calc.program("let while = 1") }
        assertEquals("whilex", (Calc.stmt("let whilex = 1") as Let).name)
        // a literal does not match the start of a word
        assertFailsWith<ParseException> { Calc.program("printx") }
    }

    @Test
    fun leftRecursionOptionsAndValues() {
        assertEquals("a.b.c", Calc.dotted("a . b.c"))
        assertEquals("a", Calc.dotted("a"))
        assertEquals(listOf("(", ")"), Calc.pair("( )"))
        assertEquals("-", Calc.optionalSign("-"))
        assertNull(Calc.optionalSign(""))
    }

    @Test
    fun predicates() {
        assertEquals("abc1", Calc.lower("abc1"))
        assertFailsWith<ParseException> { Calc.lower("Abc") }
        assertEquals("x1", Calc.notNumber("x1"))
        val error = assertFailsWith<ParseException> { Calc.notNumber("12") }
        assertEquals(listOf("not number"), error.expected)
    }

    @Test
    fun errorsTellWhereAndWhat() {
        val error = assertFailsWith<ParseException> { Calc.program("let x = 1\nprint (x +", "main.calc") }
        assertEquals(2, error.line)
        assertEquals(11, error.column)
        assertTrue("number" in error.expected && "ident" in error.expected && "'('" in error.expected, error.expected.toString())
        assertTrue(error.message!!.startsWith("main.calc:2:11: expected "), error.message)
        assertTrue(error.message!!.endsWith("found end of input"), error.message)

        val trailing = assertFailsWith<ParseException> { Calc.expr("1 + 2 )") }
        assertEquals(7, trailing.column)
        assertTrue("end of input" in trailing.expected)
    }

    @Test
    fun spansOfParsedNodes() {
        val program = Calc.program("let x = 1\n  print x + 2")
        val print = program.stmts[1] as Print
        val span = spanOf(print)!!
        assertEquals(2, span.line)
        assertEquals(3, span.column)
        assertEquals("print x + 2", span.text)
        assertEquals("x + 2", spanOf(print.value)!!.text)
        assertEquals(1, spanOf(program)!!.line)
        assertNull(spanOf(Num(1)))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun binaryFormats() {
        val header = Blob.header(
            bytes(
                0xCA, 0xFE,       // magic
                0x00, 0x2A,       // version 42
                0b1010_0111,      // flags 1010, kind 7
                0xFE,             // delta -2
                3,                // length of data
                'h'.code, 'i'.code,
                1, 2, 255,        // data
                0b1010_0101,
            )
        )
        assertEquals(Header(42, listOf(true, false, true, false), 7, -2, "hi", listOf(1, 2, 255)), header)
        assertEquals(listOf(1, 0xFFFF), Blob.words(listOf(2, 0, 1, 0xFF, 0xFF)))

        val error = assertFailsWith<ParseException> { Blob.header(bytes(0xCA, 0xFF)) }
        assertTrue(error.message!!.startsWith("byte 0: expected 0xCAFE, found 0xCA"), error.message)
        val short = assertFailsWith<ParseException> { Blob.words(listOf(2, 0, 1)) }
        assertEquals(24, short.offset)
    }
}
