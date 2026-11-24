package kiln

import java.io.File
// jvm.forge declares its parser in the module jvm: in Kotlin it is jvm__ClassFile
import kiln.jvm__ClassFile as ClassFile
import kotlin.system.exitProcess

private const val USAGE = "usage: kilnc <file.kiln> [-d <dir>] [--listing] [--cfg] [--run]"

/**
 * The Kiln compiler: `kilnc fib.kiln` writes `Fib.class`, which `java Fib` runs.
 * `--listing` prints the JVM code, `--cfg` the control flow graphs (dot), `--run` also runs the class.
 */
fun main(args: Array<String>) {
    var input: File? = null
    var outDir = File(".")
    var listing = false
    var graphs = false
    var run = false
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "-d" -> outDir = File(args.getOrNull(++i) ?: fail(USAGE))
            "--listing" -> listing = true
            "--cfg" -> graphs = true
            "--run" -> run = true
            else -> if (arg.startsWith("-") || input != null) fail(USAGE) else input = File(arg)
        }
        i++
    }
    val file = input ?: fail(USAGE)
    if (!file.isFile) fail("kilnc: can not read $file")

    // the class is named after the file, as Java does it
    val className = file.nameWithoutExtension.replaceFirstChar { it.uppercase() }
    if (!className.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) fail("kilnc: '$className' is not a valid class name; rename ${file.name}")

    val result = try {
        compile(className, file.readText())
    } catch (e: ParseException) {
        fail("${file.path}:${e.message}")
    }
    for (d in result.diagnostics) System.err.println("${file.path}:${d.line}:${d.column}: ${d.severity.label()}: ${d.message}")
    if (graphs) println(result.graphs)
    val bytes = result.bytes ?: exitProcess(1)

    if (listing) println(result.listing)

    outDir.mkdirs()
    val out = File(outDir, "$className.class")
    out.writeBytes(ByteArray(bytes.size) { bytes[it].toByte() })

    // read the class file back with the binary parser of jvm.forge
    val summary = ClassFile.file(bytes)
    println(
        "wrote ${out.path}: ${bytes.size} bytes, version ${summary.major}.${summary.minor}, " +
            "${summary.constants.size} constants, ${summary.methods} methods, ${summary.codeBytes} bytes of code"
    )

    if (run) runClass(className, bytes)
}

private fun Severity.label() = if (this == Severity.ERROR) "error" else "warning"

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

/** Loads a compiled Kiln class and runs its `main`. */
fun runClass(className: String, bytes: List<Int>) {
    val classBytes = ByteArray(bytes.size) { bytes[it].toByte() }
    val loader = object : ClassLoader(ClassLoader.getSystemClassLoader()) {
        override fun findClass(name: String): Class<*> =
            if (name == className) defineClass(name, classBytes, 0, classBytes.size) else super.findClass(name)
    }
    val main = loader.loadClass(className).getMethod("main", Array<String>::class.java)
    main.invoke(null, arrayOf<String>())
}
