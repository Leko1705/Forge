import forgetest.conformance.Chain
import forgetest.conformance.safeCalls
import kotlin.test.Test
import kotlin.test.assertEquals

/** `a?.b` in the generated code: null if `a` is, else the member. */
class SafeCallTest {

    @Test
    fun aSafeCallIsNullIfItsReceiverIs() {
        assertEquals("null null null null - null", safeCalls(null))
        assertEquals("4 6 8 2 A 11", safeCalls(Chain(4, Chain(6, null, null) { it }, "a") { it + 10 }))
        assertEquals("8 null 16 4 - 3", safeCalls(Chain(8, null, null) { it * 3 }))
    }
}
