import forgetest.basics.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BasicsTest {

    private fun program(vararg body: Expr): Program =
        Program(body.mapIndexed { i, e -> Function("f$i", listOf(Param("x"), Param("y")), e) })

    @Test
    fun synthesizedAttributesDispatchByType() {
        assertEquals(Type.INT, Plus(Num(1), Num(2)).type)
        assertEquals(Type.STR, Plus(Text("a"), Text("b")).type)
        assertEquals(Type.ERROR("can not add int and str"), Plus(Num(1), Text("b")).type)
        assertEquals("error: x", Type.ERROR("x").repr())
    }

    @Test
    fun interfaceDefaultsAndOverrides() {
        assertEquals(1, Num(1).size)
        assertEquals(5, Plus(Plus(Num(1), Num(2)), Num(3)).size)
    }

    @Test
    fun inheritedAttributesFlowDown() {
        val ref = Ref("x")
        val p = program(ref)
        assertEquals(listOf("x", "y"), ref.scope)
        assertEquals("f0", ref.function)
        assertSame(p, ref.program)
        assertEquals(Type.INT, ref.type)
        // outside of a function: the initial values
        assertEquals(emptyList(), Ref("z").scope)
        assertNull(Ref("z").function)
    }

    @Test
    fun listDelegatesBindTheIndex() {
        val a = Num(1)
        val b = Num(2)
        val c = Num(3)
        Call("f", listOf(a, b, c))
        assertEquals(listOf(0, 1, 2), listOf(a, b, c).map { it.argIndex })
        assertEquals(2, c.position)
        assertEquals(-1, Num(4).argIndex)
    }

    @Test
    fun collectionsGatherContributionsOfTheWholeTree() {
        val p = program(Plus(Ref("z"), Num(1)), Plus(Num(1), Text("s")), Num(3))
        assertEquals(
            listOf(
                Diagnostic("unknown name z", "f0"),
                Diagnostic("unknown name z", "f0"),
                Diagnostic("can not add int and str", "f1"),
            ),
            p.diags,
        )
        assertEquals(emptyList(), program(Num(1)).diags)
    }

    @Test
    fun constantsAreInlined() {
        assertEquals("hello, you", greet("you"))
        assertEquals(44, constants())
        // no declaration for a constant: its value is where it is used
        val generated = java.io.File("build/generated/forge-test/basics/basics.kt").readText()
        for (name in listOf("greeting", "answer", "six", "words", "missingType")) {
            kotlin.test.assertFalse(Regex("\\b$name\\b").containsMatchIn(generated.substringAfter("// ==== end of Forge runtime")), name)
        }
        kotlin.test.assertTrue("((6 * 7) +" in generated)
    }

    @Test
    fun failStops() {
        assertEquals(3, safeDiv(6, 2))
        val e = kotlin.test.assertFailsWith<IllegalStateException> { safeDiv(6, 0) }
        assertEquals("division by zero: 6 / 0", e.message)
    }

    @Test
    fun functionsAndPatterns() {
        assertEquals(6, sum(listOf(1, 2, 3)))
        assertEquals("empty", describe(emptyList()))
        assertEquals("one: 7", describe(listOf(7)))
        assertEquals("two", describe(listOf(1, 2)))
        assertEquals("many, starting with 1", describe(listOf(1, 2, 3)))
        assertEquals(2, lookup(mapOf("a" to 2), "a"))
        assertEquals(-1, lookup(mapOf("a" to 2), "b"))
        assertEquals(3.0f, scale(2, 1.5f))
        assertEquals("a", firstOr(listOf("a", "b"), "z"))
        assertEquals("z", firstOr(emptyList(), "z"))
    }
}
