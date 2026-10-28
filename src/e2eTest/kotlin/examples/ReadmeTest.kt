package examples

import examples.readme.eval
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadmeTest {
    @Test
    fun example() {
        assertEquals(5, eval("1 + 2 * (3 - 1)"))
        assertEquals(2, eval("8 / 2 / 2"))
    }
}
