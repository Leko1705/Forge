import com.leko.forge.parse.parseForge
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

/**
 * The targets other than Kotlin: the conformance program (src/test/forge/conformance.forge) and the Kiln
 * compiler (examples/kiln) are generated for each target, built with its toolchain and run. Their output must
 * be what the Kotlin target computes: the same report, the same class files and the same diagnostics.
 * A target whose toolchain is not installed is skipped.
 */
class TargetsTest {

    private class Toolchain(
        val target: String,
        val tools: List<String>,
        /** Builds the program in [dir] (the generated file and the driver files) and returns the command that runs it. */
        val build: (dir: File, main: String) -> List<String>,
    )

    private val toolchains = listOf(
        Toolchain("python", listOf("python3")) { _, main -> listOf("python3", "$main.py") },
        Toolchain("java", listOf("javac", "java")) { dir, main ->
            run(dir, listOf("javac", "-d", "classes") + dir.listFiles()!!.filter { it.extension == "java" }.map { it.name })
            listOf("java", "-Xss512m", "-cp", "classes", main.replaceFirstChar { it.uppercase() })
        },
        Toolchain("ocaml", listOf("ocamlopt")) { dir, main ->
            val module = dir.listFiles()!!.single { it.extension == "ml" && it.nameWithoutExtension != main }
            run(dir, listOf("ocamlopt", "-O2", module.name, "$main.ml", "-o", main), timeout = 600)
            listOf("./$main")
        },
        Toolchain("cpp", listOf("g++")) { dir, main ->
            run(dir, listOf("g++", "-std=c++17", "-O1", "-pthread", "$main.cpp", "-o", main), timeout = 600)
            listOf("./$main")
        },
        Toolchain("rust", listOf("rustc")) { dir, main ->
            run(dir, listOf("rustc", "--edition", "2021", "-O", "$main.rs", "-o", main), timeout = 600)
            listOf("./$main")
        },
        // an ES module, as in a browser; the package.json of the driver files makes Node load .js files as modules
        Toolchain("javascript", listOf("node")) { _, main -> listOf("node", "$main.js") },
    )

    private fun available(tool: String): Boolean =
        System.getenv("PATH").split(File.pathSeparator).any { File(it, tool).canExecute() }

    /** A process for [command] in [dir]; JVM options of the environment would make java print notices. */
    private fun process(dir: File, command: List<String>): Process {
        val builder = ProcessBuilder(command).directory(dir)
        builder.environment().remove("JAVA_TOOL_OPTIONS")
        builder.environment().remove("_JAVA_OPTIONS")
        return builder.start()
    }

    /** Runs [command] in [dir] and returns what it printed; it must succeed. */
    private fun run(dir: File, command: List<String>, timeout: Long = 300): String {
        val process = process(dir, command)
        val err = StringBuilder()
        val reader = Thread { err.append(process.errorStream.bufferedReader().readText()) }.also { it.start() }
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) error("timeout: $command")
        reader.join()
        if (process.exitValue() != 0) error("$command failed (${process.exitValue()}):\n$out\n$err")
        return out
    }

    /** Generates [forge] for [target] into [dir] with the Forge compiler, optimized with -opt if [optimized]. */
    private fun generate(forge: File, target: String, dir: File, optimized: Boolean) {
        dir.deleteRecursively()
        dir.mkdirs()
        val context = com.leko.forge.context.Context()
        val args = arrayOf("-f", forge.path, "-t", target, "-d", dir.path) + if (optimized) arrayOf("-opt") else emptyArray()
        com.leko.forge.utils.Options.instance(context).set(args)
        val ast = forge.parseForge()
        com.leko.forge.analysis.Analyzer.instance(context).analyze(ast)
        var module = com.leko.forge.llir.LLIR.instance(context).transform(ast)
        if (optimized) module = com.leko.forge.llir.Optimizer.instance(context).optimize(module)
        com.leko.forge.gen.Generator.instance(context).generate(module)
    }

    private val variants = listOf(false to "", true to " optimized")

    private fun program(toolchain: Toolchain, dir: File, main: String): List<String> = toolchain.build(dir, main)

    @TestFactory
    fun conformance(): List<DynamicTest> = toolchains.flatMap { toolchain -> variants.map { (optimized, label) ->
        DynamicTest.dynamicTest(toolchain.target + label) {
            assumeTrue(toolchain.tools.all { available(it) }, "${toolchain.tools} not installed")
            val dir = File("build/targets/${toolchain.target}/conformance$label".replace(" ", "-"))
            generate(File("src/test/forge/conformance.forge"), toolchain.target, dir, optimized)
            File("src/test/targets/${toolchain.target}").listFiles()!!.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            val output = run(dir, program(toolchain, dir, "main"))
            val expected = forgetest.conformance.report().joinToString("") { it + "\n" }
            assertEquals(expected, output)
        }
    } }

    @TestFactory
    fun kiln(): List<DynamicTest> = toolchains.flatMap { toolchain -> variants.map { (optimized, label) ->
        DynamicTest.dynamicTest(toolchain.target + label) {
            assumeTrue(toolchain.tools.all { available(it) }, "${toolchain.tools} not installed")
            val dir = File("build/targets/${toolchain.target}/kiln$label".replace(" ", "-"))
            generate(File("examples/kiln/kiln.forge"), toolchain.target, dir, optimized)
            File("examples/kiln/${toolchain.target}").listFiles()!!.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            val command = program(toolchain, dir, "kilnc")
            for (name in listOf("hello", "fib", "primes")) {
                val source = File("examples/kiln/programs/$name.kiln")
                run(dir, command + listOf(source.absolutePath, "-d", "out"))
                val className = name.replaceFirstChar { it.uppercase() }
                val expected = kiln.compile(className, source.readText()).bytes!!.map { it.toByte() }.toByteArray()
                assertContentEquals(expected, File(dir, "out/$className.class").readBytes(), "$className.class")
            }
            // diagnostics: the same messages; the compiler fails
            val errors = File("src/test/targets/kiln/errors.kiln")
            val process = process(dir, command + listOf(errors.absolutePath, "-d", "out"))
            val stderr = process.errorStream.bufferedReader().readText()
            process.waitFor(300, TimeUnit.SECONDS)
            assertEquals(1, process.exitValue())
            val expected = kiln.compile("Errors", errors.readText()).diagnostics.joinToString("") {
                "${errors.absolutePath}:${it.line}:${it.column}: ${if (it.severity == kiln.Severity.ERROR) "error" else "warning"}: ${it.message}\n"
            }
            assertEquals(expected, stderr)
        }
    } }
}
