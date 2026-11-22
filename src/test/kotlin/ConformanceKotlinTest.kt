import kotlin.test.Test

class ConformanceKotlinTest {
    @Test
    fun printReport() {
        java.io.File("build/conformance-kotlin.txt").writeText(forgetest.conformance.report().joinToString("\n") + "\n")
    }
}
