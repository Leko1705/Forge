import forgetest.conformance.Chain
import forgetest.conformance.safeCalls
import forgetest.conformance.smartCasts
import kotlin.test.Test
import kotlin.test.assertEquals

/** `a?.b` and smart casts in the generated code. */
class SafeCallTest {

    @Test
    fun aSafeCallIsNullIfItsReceiverIs() {
        assertEquals("null null null null - null", safeCalls(null))
        assertEquals("4 6 8 2 A 11", safeCalls(Chain(4, Chain(6, null, null) { it }, "a") { it + 10 }))
        assertEquals("8 null 16 4 - 3", safeCalls(Chain(8, null, null) { it * 3 }))
    }

    /** After `x != null` (or in the `else` of `x == null`), x is taken as not null. */
    @Test
    fun smartCasts() {
        assertEquals("none none - true 0", smartCasts(null, null))
        assertEquals("6 10 2 true 5", smartCasts(5, Chain(1, Chain(2, null, null) { it }, null) { it }))
        assertEquals("3 4 - false 5", smartCasts(2, Chain(1, null, null) { it }))
    }
}
