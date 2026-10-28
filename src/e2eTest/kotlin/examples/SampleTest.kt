package examples

import examples.sample.Diagnostic
import examples.sample.Plus
import examples.sample.SampleLang
import examples.sample.Type
import examples.sample.compile
import examples.sample.repr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SampleTest {

    @Test
    fun wellTypedProgram() {
        val result = compile(
            """
            fun main(a: int, b: str): int = 1 + 2 + 3
            fun str.length(): str = this + "!"   // `this` has the receiver type
            fun nothing(): str = "a" + "b"
            """
        )
        assertEquals(emptyList(), result.diags)
        assertEquals(listOf(2, 0, 0), result.compiled)
    }

    @Test
    fun typeErrorsAreCollected() {
        val result = compile(
            """
            fun f(): int = 1 + "x"
            fun g(): int = this
            fun int.h(): int = this + 1
            """
        )
        assertNull(result.compiled)
        assertEquals(
            listOf(Diagnostic("can not add int and str"), Diagnostic("can not use 'this' in this context")),
            result.diags,
        )
    }

    @Test
    fun synthesizedAttributesOnTheTree() {
        val root = SampleLang.program("fun f(): int = 1 + 2 + 3")
        val body = assertIs<Plus>(root.decls.single().let { (it as examples.sample.Function).body })
        // + is left associative (@left)
        assertIs<Plus>(body.lhs)
        assertEquals(Type.INT, body.type)
        assertEquals("int", body.type.repr())
    }
}
