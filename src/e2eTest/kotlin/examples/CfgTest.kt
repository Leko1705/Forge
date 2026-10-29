package examples

import examples.cfg.Assign
import examples.cfg.CfgLang
import examples.cfg.If
import examples.cfg.Print
import examples.cfg.While
import examples.cfg.compile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CfgTest {

    @Test
    fun graphConstruction() {
        val program = CfgLang.program(
            """
            i := 0;
            while i < 3 do
                if i == 1 then break; end
                i := i + 1;
            end
            print i;
            """.trimIndent()
        )
        val (init, loop, print) = program.body
        loop as While
        val (branch, increment) = loop.body
        branch as If
        assertEquals(listOf(loop), init.succ)
        assertEquals(listOf(branch, print), loop.succ)                 // [body, after the loop]
        assertEquals(listOf(branch.yes[0], increment), branch.succ)    // [then, fall through]
        assertEquals(listOf(print), branch.yes[0].succ)                // break leaves the loop
        assertEquals(listOf(loop), increment.succ)                     // back edge
        assertEquals(listOf(program.exit), print.succ)
        assertEquals(setOf(init, increment), loop.pred.toSet())
        assertEquals(setOf(loop, branch.yes[0]), print.pred.toSet())
        assertEquals(listOf("i"), loop.liveIn)
        assertEquals(emptyList(), print.liveOut)
    }

    @Test
    fun generatedCodeRuns() {
        val result = compile(
            """
            // sum of 1..10 and the first square above 50
            n := 0; sum := 0;
            while n < 10 do n := n + 1; sum := sum + n; end
            print sum;
            k := 0;
            while 1 do
                k := k + 1;
                if 50 < k * k then break; end
            end
            print k * k;
            """.trimIndent()
        )
        assertEquals(emptyList(), result.warnings)
        assertEquals(emptyList(), result.uninitialized)
        assertEquals(listOf(55, 64), execute(result.code))
    }

    @Test
    fun deadStoresAreReportedAndNotGenerated() {
        val result = compile(
            """
            x := 1;          // dead: overwritten before any read
            x := 2;
            y := x * 10;     // dead: y is never read
            print x;
            """.trimIndent()
        )
        assertEquals(
            listOf("1:1: value assigned to 'x' is never used", "3:1: value assigned to 'y' is never used"),
            result.warnings,
        )
        assertTrue(result.code.none { it == "store y" || it == "push 1" }, result.code.toString())
        assertEquals(listOf(2), execute(result.code))
    }

    @Test
    fun unreachableCodeAndUninitializedVariables() {
        val result = compile(
            """
            while 1 do
                break;
                print 1;     // unreachable
            end
            if a then print b; else print 2; end
            """.trimIndent()
        )
        assertEquals(listOf("3:5: unreachable statement"), result.warnings)
        assertEquals(listOf("a", "b"), result.uninitialized)
    }

    @Test
    fun graphvizExport() {
        val dot = CfgLang.program("x := 1; while x < 3 do x := x + 1; end print x;").dot
        assertTrue(dot.startsWith("digraph cfg {"), dot)
        assertTrue("L1 -> L2;" in dot && "L2 -> L1;" in dot, dot)   // loop header and back edge
        assertTrue("live: x" in dot, dot)
    }
}
