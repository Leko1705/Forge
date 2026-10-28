package examples

import examples.calc.Bin
import examples.calc.Calc
import examples.calc.Neg
import examples.calc.Num
import examples.calc.run
import forge.runtime.ForgeParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CalcTest {

    @Test
    fun evaluatesPrograms() {
        val result = run(
            """
            # comments are layout
            let x = 1 + 2 * 3;      # 7
            let y = (1 + 2) * 3;    # 9
            print x; print y;
            print 2 ^ 3 ^ 2;        # right associative: 2^9
            print -2 ^ 2;           # unary minus binds weaker than ^
            print 10 - 4 - 3;       # left associative
            print 7 % 4 + x * y;
            """
        )
        assertEquals(listOf(7, 9, 512, -4, 3, 66), result.output)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun reportsErrorsThroughCollectionAttributes() {
        val result = run("print a; let b = 1 / 0; print b + c;")
        assertEquals(listOf(0, 0), result.output)
        assertEquals(listOf("undefined variable 'a'", "division by zero", "undefined variable 'c'"), result.errors)
    }

    @Test
    fun variablesAreOnlyVisibleAfterTheirDefinition() {
        val result = run("print v; let v = 5; print v; let v = v + 1; print v;")
        assertEquals(listOf(0, 5, 6), result.output)
        assertEquals(listOf("undefined variable 'v'"), result.errors)
    }

    @Test
    fun everyRuleIsAnEntryPoint() {
        val e = Calc.expr("1 - -2 * 3")
        assertIs<Bin>(e)
        assertEquals("-", e.op)
        assertIs<Num>(e.lhs)
        val rhs = assertIs<Bin>(e.rhs)
        assertIs<Neg>(rhs.lhs)
        assertEquals(7, e.value)
    }

    @Test
    fun keywordsCanBeUsedAsIdentifiersWhereTheyAreNotKeywords() {
        val result = run("let print = 3; print print;")
        assertEquals(listOf(3), result.output)
    }

    @Test
    fun syntaxErrors() {
        val e = assertFailsWith<ForgeParseException> { Calc.program("let x = ;") }
        assertEquals(1, e.line)
        assertEquals(9, e.column)
    }
}
