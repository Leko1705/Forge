package com.leko.forge.parse

import ForgeLexer
import ForgeParser
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import java.io.File

fun File.parseForge(): ForgeParser.RootContext = parseForge(CharStreams.fromFileName(this.absolutePath))

/** Parses Forge source code, e.g. in tests. */
fun String.parseForge(sourceName: String = "<string>"): ForgeParser.RootContext =
    parseForge(CharStreams.fromString(this, sourceName))

private fun parseForge(input: CharStream): ForgeParser.RootContext {
    val lexer = ForgeLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = ForgeParser(tokens)
    return parser.root()
}
