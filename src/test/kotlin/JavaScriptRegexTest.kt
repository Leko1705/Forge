import com.leko.forge.gen.jsRegex
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The JavaScript target translates the Java regular expressions of terminals into JavaScript ones. A terminal
 * matches at a position (`lookingAt`): the translation must match the same text as Java does, which Node
 * checks. Skipped if `node` is not installed.
 */
class JavaScriptRegexTest {

    private val cases = listOf(
        "\\s+" to "  \t\nx",
        "\\s+" to " x",                   // not a space in Java
        "\\S+" to "ab  c",
        "[\\s,]+" to " ,\t,x",
        "." to "\u0085",                       // a line terminator in Java
        "a.c" to "abc",
        "\\w+" to "abc_1é",
        "\\d+" to "123٤",
        "\\p{Alpha}+" to "abcD1",
        "\\p{Upper}\\p{Lower}*" to "Hello",
        "[\\p{Digit}x]+" to "1x2y",
        "\\P{Digit}+" to "ab1",
        "\\Qa+b\\E" to "a+b",
        "\\Q(x\\E\\d" to "(x1",
        "abc$" to "abc\n",
        "abc$" to "abc",
        "abc\\z" to "abc",
        "abc\\Z" to "abc\n",
        "\\Aab" to "ab",
        "a*+b" to "aaab",
        "a++" to "aaa",
        "a?+b" to "ab",
        "a{2}+" to "aaa",
        "a+?" to "aaa",
        "(?:ab)+" to "ababx",
        "(?=a)\\w" to "ab",
        "(?!b)\\w" to "ab",
        "(?<word>\\w+)-\\k<word>" to "ab-ab",
        "(?>ab|a)c" to "abc",
        "(?i)select" to "SeLeCt",
        "[^\"\\\\]*" to "ab\"c",
        "\"(\\\\.|[^\"\\\\])*\"" to "\"a\\\"b\" rest",
        "[]a]+" to "]a]b",
        "[a-z&&[^b]]" to "a",
        "\\h+" to " \t x",
        "\\R" to "\r\nx",
        "\\e\\a" to "\u001b\u0007",
        "\\0101" to "A",
        "\\x{41}" to "A",
        "\\x41" to "A",
        "a/b" to "a/b",
        "//[^\\n]*" to "// comment\nx",
        "(\\s|//[^\\n]*)+" to "  // c\n  x",
        "[+-]?[0-9]+" to "-12a",
        "=(?!=)" to "=1",
        "\\{\\}" to "{}",
        "x{1,2}y" to "xxy",
        "\\bab\\b" to "ab c",
    )

    private fun available(tool: String): Boolean =
        System.getenv("PATH").split(File.pathSeparator).any { File(it, tool).canExecute() }

    @Test
    fun translationMatchesJava() {
        assumeTrue(available("node"), "node not installed")
        val supported = cases.filter { (regex, _) -> regex != "[a-z&&[^b]]" }
        val expected = supported.map { (regex, input) ->
            val m = java.util.regex.Pattern.compile(regex).matcher(input)
            if (m.lookingAt()) m.end() else -1
        }
        val script = buildString {
            append("const cases = [\n")
            for ((regex, input) in supported) {
                val (source, flags) = jsRegex(regex)
                append("  [").append(js(source)).append(", ").append(js(flags)).append(", ").append(js(input)).append("],\n")
            }
            append("];\n")
            append("for (const [source, flags, input] of cases) {\n")
            append("  const m = new RegExp(source, 'y' + flags).exec(input);\n")
            append("  console.log(m === null ? -1 : m[0].length);\n")
            append("}\n")
        }
        val file = File.createTempFile("regex", ".js")
        try {
            file.writeText(script)
            val process = ProcessBuilder("node", file.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            assertEquals(0, process.exitValue(), output)
            val actual = output.lines().filter { it.isNotBlank() }.map { it.trim().toInt() }
            val report = supported.indices.filter { expected[it] != actual.getOrNull(it) }
                .joinToString("\n") { "/${supported[it].first}/ on ${js(supported[it].second)}: Java ${expected[it]}, JavaScript ${actual.getOrNull(it)} (${jsRegex(supported[it].first).first})" }
            assertEquals("", report)
        } finally {
            file.delete()
        }
    }

    private fun js(text: String): String = buildString {
        append('"')
        for (c in text) {
            if (c == '"' || c == '\\') append('\\').append(c)
            else if (c.code < 32 || c.code > 126) append("\\u%04x".format(c.code))
            else append(c)
        }
        append('"')
    }
}
