package examples

import examples.features.Docs
import examples.features.Group
import examples.features.Word
import examples.features.classify
import examples.features.footerText
import examples.features.mixed
import examples.features.shapes
import kotlin.test.Test
import kotlin.test.assertEquals

class FeaturesTest {

    @Test
    fun attributes() {
        val doc = Docs.doc("a b 2.5 g { a, h { b, 3 } } -- end")
        assertEquals(4, doc.stats.words)
        assertEquals(1.0 + 1.0 + 2.5 + (1.0 + (1.0 + 3.0)), doc.stats.total)
        assertEquals(mapOf("a" to 2, "b" to 2, "end" to 1), doc.stats.names)
        assertEquals(2, doc.deepest)
        assertEquals("g{a,h{b,3.0}}", (doc.items[3] as Group).describe)
        assertEquals("end", footerText(doc))
        assertEquals("-", footerText(Docs.doc("x")))
        // optional children get parents too
        assertEquals(doc, doc.footer!!.forgeParent)
        assertEquals(1, (doc.footer as Word).forgeLine)
        assertEquals(32, (doc.footer as Word).forgeColumn)
    }

    @Test
    fun enumsAndPatterns() {
        assertEquals(listOf(3.0, 7.0, 0.0, 0.0), shapes())
        assertEquals("empty", classify(listOf()))
        assertEquals("one:4", classify(listOf(4)))
        assertEquals("pair", classify(listOf(2, 2)))
        assertEquals("many:3", classify(listOf(1, 2)))
        assertEquals("many:5", classify(listOf(2, 3, 4)))
    }

    @Test
    fun numericConversions() {
        assertEquals(3 * 1.5 + 1 + 1, mixed(3, 1.5))
    }
}
