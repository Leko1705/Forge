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

/**
 * Parses a Forge file together with the files it imports (`import "jvm.forge"`, relative to the importing file),
 * transitively. The result is one root holding the declarations of all files, imported ones first; each file is
 * read once, even if it is imported several times.
 */
fun File.parseForge(): ForgeParser.RootContext {
    val main = parseChecked(this)
    val seen = mutableSetOf(this.canonicalFile)
    val imported = mutableListOf<ForgeParser.RootContext>()

    fun collect(file: File, root: ForgeParser.RootContext) {
        for (decl in root.importDecl()) {
            val path = decl.path.text.let { it.substring(1, it.length - 1) }
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
    if (imported.isEmpty()) return main

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

private fun parseChecked(file: File): ForgeParser.RootContext {
    val lexer = ForgeLexer(CharStreams.fromFileName(file.absolutePath))
    val parser = ForgeParser(CommonTokenStream(lexer))
    val root = parser.root()
    // the syntax errors were reported by ANTLR; the analysis would fail on the incomplete tree
    val errors = parser.numberOfSyntaxErrors
    if (errors > 0) throw ImportException("${file.name}: $errors syntax error(s)")
    return root
}

/** Parses Forge source code, e.g. in tests. Imports are not resolved. */
fun String.parseForge(sourceName: String = "<string>"): ForgeParser.RootContext =
    parseForge(CharStreams.fromString(this, sourceName))

private fun parseForge(input: CharStream): ForgeParser.RootContext {
    val lexer = ForgeLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = ForgeParser(tokens)
    return parser.root()
}
