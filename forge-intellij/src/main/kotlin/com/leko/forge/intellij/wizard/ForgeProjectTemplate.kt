package com.leko.forge.intellij.wizard

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** The targets of the compiler (`-t`). */
enum class ForgeTarget(val id: String, val title: String) {
    KOTLIN("kotlin", "Kotlin"), JAVA("java", "Java"), PYTHON("python", "Python"), CPP("cpp", "C++"),
    RUST("rust", "Rust"), OCAML("ocaml", "OCaml");

    override fun toString(): String = title
}

enum class ForgeProjectKind(val title: String) {
    SAMPLE("Sample: an expression language"), EMPTY("Empty");

    override fun toString(): String = title
}

/** What the wizard creates. [packageName] is the package (namespace) of the generated code. */
data class ForgeProjectOptions(
    val name: String,
    val kind: ForgeProjectKind = ForgeProjectKind.SAMPLE,
    val target: ForgeTarget = ForgeTarget.KOTLIN,
    val packageName: String = defaultPackage(name),
    val gradle: Boolean = true,
) {
    /** The name of the .forge file: the project name as an identifier. */
    val fileName: String get() = name.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "main" }.let { if (it[0].isDigit()) "_$it" else it }

    companion object {
        fun defaultPackage(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9_.]"), "").trim('.').ifEmpty { "lang" }.let { if (it[0].isDigit()) "_$it" else it }
    }
}

/**
 * The files of a new Forge project: the program in `src/main/forge`, and, with [ForgeProjectOptions.gradle], a
 * Gradle build that runs the Forge compiler bundled with the plugin (copied to `lib/forge-compiler.jar`) and, for
 * Kotlin, compiles the generated code with a `main` that uses it.
 */
object ForgeProjectTemplate {

    /** The version of Kotlin the compiler is built with; its jar needs this standard library. */
    const val KOTLIN_VERSION = "2.3.10"
    const val ANTLR_VERSION = "4.13.1"

    private fun resource(name: String): ByteArray =
        ForgeProjectTemplate::class.java.getResourceAsStream("/projectTemplate/$name")!!.use { it.readBytes() }

    /** The text files of the project, by their path relative to the project directory. */
    fun textFiles(options: ForgeProjectOptions): Map<String, String> = buildMap {
        val forgeFile = "src/main/forge/${options.fileName}.forge"
        put(forgeFile, program(options))
        put(".gitignore", listOf("build/", ".gradle/", ".kotlin/", "out/", ".idea/workspace.xml", ".idea/shelf/", "").joinToString("\n"))
        put("README.md", readme(options, forgeFile))
        if (options.gradle) {
            put("settings.gradle.kts", "rootProject.name = \"${options.name.replace("\"", "")}\"\n")
            put("build.gradle.kts", buildScript(options, forgeFile))
            put("gradle/wrapper/gradle-wrapper.properties", String(resource("gradle-wrapper.properties")))
            put("gradlew", String(resource("gradlew")))
            put("gradlew.bat", String(resource("gradlew.bat")))
            if (options.target == ForgeTarget.KOTLIN) {
                put("src/main/kotlin/${options.packageName.replace('.', '/')}/Main.kt", main(options))
            }
        }
    }

    /**
     * Writes the project to [directory]. [compilerJar] is the jar of the Forge compiler; `null` if there is none
     * (the README says how to get one).
     */
    fun write(directory: Path, options: ForgeProjectOptions, compilerJar: Path?) {
        for ((path, text) in textFiles(options)) {
            val file = directory.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        if (options.gradle) {
            Files.write(directory.resolve("gradle/wrapper/gradle-wrapper.jar"), resource("gradle-wrapper.jar"))
            runCatching {
                val gradlew = directory.resolve("gradlew")
                Files.setPosixFilePermissions(gradlew, Files.getPosixFilePermissions(gradlew) + PosixFilePermission.OWNER_EXECUTE + PosixFilePermission.GROUP_EXECUTE)
            }
            if (compilerJar != null) {
                Files.createDirectories(directory.resolve("lib"))
                Files.copy(compilerJar, directory.resolve("lib/forge-compiler.jar"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun program(options: ForgeProjectOptions): String = when (options.kind) {
        ForgeProjectKind.SAMPLE -> String(resource("sample.forge")).replace("{{name}}", options.fileName)
        ForgeProjectKind.EMPTY -> """
            |// ${options.fileName}.forge
            |
            |/** The root of the tree. */
            |node Program(decls: List<Decl>)
            |node Decl
            |""".trimMargin()
    }

    private fun main(options: ForgeProjectOptions): String = when (options.kind) {
        ForgeProjectKind.SAMPLE -> """
            |package ${options.packageName}
            |
            |/** Evaluates the expressions of the arguments, or of a few examples, with the code generated from ${options.fileName}.forge. */
            |fun main(args: Array<String>) {
            |    val source = args.joinToString(" ").ifBlank { "1 + 2 * 3  -(4 + 5) * 2" }
            |    for (line in evaluate(source)) println(line)
            |}
            |""".trimMargin()
        ForgeProjectKind.EMPTY -> """
            |package ${options.packageName}
            |
            |/** Uses the code generated from ${options.fileName}.forge. */
            |fun main() {
            |    val program = Program(emptyList())
            |    println("${'$'}{program.decls.size} declarations")
            |}
            |""".trimMargin()
    }

    private fun buildScript(options: ForgeProjectOptions, forgeFile: String): String {
        val kotlin = options.target == ForgeTarget.KOTLIN
        return buildString {
            appendLine("// Runs the Forge compiler (lib/forge-compiler.jar) on $forgeFile; the generated code is in build/generated/forge.")
            appendLine("plugins {")
            if (kotlin) {
                appendLine("    kotlin(\"jvm\") version \"$KOTLIN_VERSION\"")
                appendLine("    application")
            } else {
                appendLine("    base")
            }
            appendLine("}")
            appendLine()
            appendLine("repositories {")
            appendLine("    mavenCentral()")
            appendLine("}")
            appendLine()
            appendLine("// the compiler and what it needs to run")
            appendLine("val forgeCompiler by configurations.creating")
            appendLine("dependencies {")
            appendLine("    forgeCompiler(files(\"lib/forge-compiler.jar\"))")
            appendLine("    forgeCompiler(\"org.antlr:antlr4-runtime:$ANTLR_VERSION\")")
            appendLine("    forgeCompiler(\"org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION\")")
            appendLine("}")
            appendLine()
            appendLine("val forgeOutput = layout.buildDirectory.dir(\"generated/forge\")")
            appendLine("val generateForge by tasks.registering(JavaExec::class) {")
            appendLine("    group = \"forge\"")
            appendLine("    description = \"Compiles the Forge program to ${options.target.title}\"")
            appendLine("    classpath = forgeCompiler")
            appendLine("    mainClass.set(\"MainKt\")")
            appendLine("    val source = file(\"$forgeFile\")")
            appendLine("    inputs.dir(\"src/main/forge\")")
            appendLine("    outputs.dir(forgeOutput)")
            appendLine("    doFirst { delete(forgeOutput) }")
            appendLine("    args(\"-f\", source.absolutePath, \"-d\", forgeOutput.get().asFile.absolutePath, \"-p\", \"${options.packageName}\", \"-t\", \"${options.target.id}\")")
            appendLine("}")
            appendLine()
            if (kotlin) {
                appendLine("kotlin.sourceSets.main { kotlin.srcDir(forgeOutput) }")
                appendLine("tasks.named(\"compileKotlin\") { dependsOn(generateForge) }")
                appendLine()
                appendLine("application {")
                appendLine("    mainClass.set(\"${options.packageName}.MainKt\")")
                appendLine("}")
            } else {
                appendLine("tasks.named(\"assemble\") { dependsOn(generateForge) }")
            }
        }
    }

    private fun readme(options: ForgeProjectOptions, forgeFile: String): String = buildString {
        appendLine("# ${options.name}")
        appendLine()
        appendLine("A compiler written in [Forge](https://github.com/Leko1705/Forge): `$forgeFile` declares the tree of the")
        appendLine("language, its attributes and its parser; Forge generates ${options.target.title} code from it.")
        appendLine()
        if (options.gradle) {
            appendLine("```")
            appendLine("./gradlew generateForge   # writes the generated code to build/generated/forge")
            if (options.target == ForgeTarget.KOTLIN) appendLine("./gradlew run              # compiles it with src/main/kotlin and runs Main.kt")
            else appendLine("./gradlew assemble         # the same")
            appendLine("```")
            appendLine()
            appendLine("The build runs the Forge compiler in `lib/forge-compiler.jar`, the one of the IDE plugin that created the")
            appendLine("project. To use another version, replace it with the jar of `./gradlew jar` in the Forge repository.")
        } else {
            appendLine("Compile it with the Forge compiler:")
            appendLine()
            appendLine("```")
            appendLine("forge -f $forgeFile -d out -p ${options.packageName} -t ${options.target.id}")
            appendLine("```")
        }
    }
}
