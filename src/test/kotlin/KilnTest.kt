import kiln.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kiln (examples/kiln): programs are compiled to class files, which are loaded and run. */
class KilnTest {

    /** Compiles [source] to the class `Test`, runs its `main` and returns what it printed. */
    private fun run(source: String): String {
        val result = compile("Test", source)
        val bytes = assertNotNull(result.bytes, "errors: " + result.diagnostics.map { it.repr() })
        val out = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(out, true))
        try {
            runClass("Test", bytes)
        } finally {
            System.setOut(old)
        }
        return out.toString().replace("\r\n", "\n")
    }

    private fun diagnostics(source: String): List<String> = compile("Test", source).diagnostics.map { it.repr() }

    @Test
    fun theExampleProgramsRun() {
        assertEquals("Hello from Kiln, running on the JVM!\n", run(File("examples/kiln/programs/hello.kiln").readText()))
        val fib = run(File("examples/kiln/programs/fib.kiln").readText()).lines()
        assertEquals("fib(0) = 0", fib.first())
        assertEquals("fib(15) = 610", fib[15])
        assertEquals(
            listOf(
                "primes: 2 3 5 7 11 13 17 19 23 29",
                "1 2 Fizz 4 Buzz Fizz 7 8 Fizz Buzz 11 Fizz 13 14 FizzBuzz ",
                "big * 3 = 3000000, neg / 7 = -5714, 17 is prime: true",
                "true",
                "",
            ),
            run(File("examples/kiln/programs/primes.kiln").readText()).lines(),
        )
    }

    @Test
    fun expressions() {
        assertEquals("7\n-1\n2\n1\n", run("fun main() { print(1 + 2 * 3)  print(-(3 - 2))  print(17 / 7)  print(17 % 8) }"))
        assertEquals("true\nfalse\ntrue\nfalse\n", run("fun main() { print(1 < 2)  print(2 <= 1)  print(3 == 3)  print(3 != 3) }"))
        assertEquals("false\ntrue\ntrue\n", run("fun main() { print(true && false)  print(false || true)  print(!false) }"))
        assertEquals("ab1true\ntrue\nfalse\n", run("fun main() { print(\"a\" + \"b\" + 1 + true)  print(\"x\" == \"x\")  print(\"x\" != \"x\") }"))
        // constants of every size: iconst, bipush, sipush and ldc
        assertEquals("5\n100\n-30000\n123456789\n-2147483648\n", run("fun main() { print(5)  print(100)  print(-30000)  print(123456789)  print(-2147483647 - 1) }"))
        // && and || do not evaluate their right operand if the left one decides
        assertEquals("false\n", run("fun boom(): bool { print(\"boom\")  return true }\nfun main() { print(false && boom()) }"))
    }

    @Test
    fun statements() {
        assertEquals("10\n", run("fun main() { var s = 0  var i = 1  while i <= 4 { s = s + i  i = i + 1 }  print(s) }"))
        assertEquals("3\n", run("fun main() { var i = 0  while true { i = i + 1  if i == 3 { break } }  print(i) }"))
        assertEquals("b\n", run("fun main() { var x = 2  if x == 1 { print(\"a\") } else if x == 2 { print(\"b\") } else { print(\"c\") } }"))
        // a block opens a scope: its variables can be declared again after it
        assertEquals("1\nx\n", run("fun main() { { var a = 1  print(a) }  var a = \"x\"  print(a) }"))
        assertEquals("hi kiln\n", run("fun greet(who: str): str { return \"hi \" + who }\nfun main() { print(greet(\"kiln\")) }"))
        assertEquals("", run("fun nothing() { return }\nfun main() { nothing() }"))
    }

    @Test
    fun theStackDepthIsAFixpoint() {
        val result = compile("Test", "fun f(a: int, b: int): int { while a < b { a = a + (b - (a * 2)) } return a }\nfun main() { print(f(1, 2)) }")
        val listing = assertNotNull(result.listing)
        assertTrue("f(II)I  // stack 4, locals 2" in listing, listing)
    }

    @Test
    fun errors() {
        assertEquals(listOf("error at 1:1: a program needs a function 'main'"), diagnostics("fun f() { }"))
        assertEquals(listOf("error at 1:20: unknown variable 'y'"), diagnostics("fun main() { print(y) }"))
        assertEquals(listOf("error at 1:20: can not apply '-' to str and int"), diagnostics("fun main() { print(\"a\" - 1) }"))
        assertEquals(listOf("error at 2:14: 'f' takes (int), not (str)"), diagnostics("fun f(x: int) { }\nfun main() { f(\"s\") }"))
        assertEquals(listOf("error at 1:14: a condition must be bool, not int"), diagnostics("fun main() { if 1 { } }"))
        assertEquals(listOf("error at 1:14: 'break' outside of a loop"), diagnostics("fun main() { break }"))
        assertEquals(listOf("error at 1:16: can not return str from a function returning int"), diagnostics("fun f(): int { return \"s\" }\nfun main() { }"))
        assertEquals(listOf("error at 1:1: 'f' must return a value"), diagnostics("fun f(x: int): int { if x > 0 { return 1 } }\nfun main() { }"))
        // an endless loop does not fall off the end
        assertEquals(emptyList(), diagnostics("fun f(): int { while true { } }\nfun main() { }"))
        assertNull(compile("Test", "fun main() { x = 1 }").bytes)
    }

    @Test
    fun warnings() {
        assertEquals(listOf("warning at 1:30: unreachable code"), diagnostics("fun main() { while true { }  print(1) }"))
        assertEquals(listOf("warning at 1:14: 'x' is never read"), diagnostics("fun main() { var x = 1 }"))
        assertEquals(
            listOf("warning at 1:14: 'x' is never read", "warning at 1:25: the value assigned to 'x' is never read"),
            diagnostics("fun main() { var x = 1  x = 2  x = 3  print(x) }"),
        )
        // warnings do not stop the compilation
        assertNotNull(compile("Test", "fun main() { var x = 1 }").bytes)
    }

    @Test
    fun syntaxErrors() {
        val e = assertFailsWith<ParseException> { compile("Test", "fun main() {\n  print(1 +)\n}") }
        assertEquals(2, e.line)
        assertTrue("found ')'" in e.message!!, e.message)
    }

    @Test
    fun classFilesAreReadBack() {
        val bytes = assertNotNull(compile("Test", "fun main() { print(\"x\" + 100000) }").bytes)
        val summary = ClassFile.file(bytes)
        assertEquals(49, summary.major)
        assertEquals(1, summary.methods)
        assertTrue("Integer" in summary.constants && "Methodref" in summary.constants && "Fieldref" in summary.constants)
        assertEquals(listOf("I", "Ljava/lang/String;", "[I", "V"), Descriptor.method("(ILjava/lang/String;[I)V"))
    }
}
