package com.leko.forge.parse

import ForgeLexer
import ForgeParser
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import java.io.File

/** A file that can not be compiled before analysis: an `import` of a missing file, or syntax errors. */
class ImportException(message: String) : RuntimeException(message)

/** The source name of the standard library, which is part of every program; it is bundled with the compiler. */
const val STD_SOURCE = "std.forge"

/**
 * Parses a Forge file together with the files it imports (`import "jvm.forge"`, relative to the importing file),
 * transitively, and the standard library. The result is one root holding the declarations of all files, the
 * standard library and imported files first; each file is read once, even if it is imported several times.
 * `import "std"` is allowed, but the standard library is always there.
 */
fun File.parseForge(): ForgeParser.RootContext = parseForgeImports(parseChecked(this))

/**
 * Like [File.parseForge], for [main], the already parsed text of this file (e.g. the unsaved text of an editor):
 * the imported files and the standard library are added. The imported files are read from disk.
 */
fun File.parseForgeImports(main: ForgeParser.RootContext): ForgeParser.RootContext {
    val seen = mutableSetOf(this.canonicalFile)
    val imported = mutableListOf(parseStd())

    fun collect(file: File, root: ForgeParser.RootContext) {
        for (decl in root.importDecl()) {
            val path = decl.path.text.let { it.substring(1, it.length - 1) }
            if (path == "std") continue
            val target = File(file.absoluteFile.parentFile, path).canonicalFile
            if (!seen.add(target)) continue
            if (!target.isFile) {
                throw ImportException("${file.name}:${decl.start.line}:${decl.start.charPositionInLine}: can not import '$path': ${target.path} does not exist")
            }
            val ast = parseChecked(target)
            collect(target, ast)
            imported += ast
        }
    }
    collect(this, main)
    return merge(imported, main)
}

/** One root with the declarations of [imported], then those of [main]; positions are those of [main]. */
private fun merge(imported: List<ForgeParser.RootContext>, main: ForgeParser.RootContext): ForgeParser.RootContext {
    val merged = ForgeParser.RootContext(null, 0)
    merged.start = main.start
    merged.stop = main.stop
    for (root in imported + main) {
        for (child in root.children.orEmpty()) {
            if (child !is ParserRuleContext || child is ForgeParser.ImportDeclContext) continue
            child.parent = merged
            merged.addAnyChild(child)
        }
    }
    return merged
}

private val stdText: String by lazy {
    ImportException::class.java.getResourceAsStream("/forge/std.forge")!!.readBytes().toString(Charsets.UTF_8)
}

private fun parseStd(): ForgeParser.RootContext = parseForge(CharStreams.fromString(stdText, STD_SOURCE))

private fun parseChecked(file: File): ForgeParser.RootContext {
    val lexer = ForgeLexer(CharStreams.fromFileName(file.absolutePath))
    val parser = ForgeParser(CommonTokenStream(lexer))
    val root = parser.root()
    // the syntax errors were reported by ANTLR; the analysis would fail on the incomplete tree
    val errors = parser.numberOfSyntaxErrors
    if (errors > 0) throw ImportException("${file.name}: $errors syntax error(s)")
    return root
}

/** Parses Forge source code, e.g. in tests, with the standard library. Imports are not resolved. */
fun String.parseForge(sourceName: String = "<string>"): ForgeParser.RootContext =
    merge(listOf(parseStd()), parseForge(CharStreams.fromString(this, sourceName)))

private fun parseForge(input: CharStream): ForgeParser.RootContext {
    val lexer = ForgeLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = ForgeParser(tokens)
    return parser.root()
}

/** Whether a declaration is one of the standard library. */
fun ParserRuleContext.isStd(): Boolean = start?.tokenSource?.sourceName == STD_SOURCE
