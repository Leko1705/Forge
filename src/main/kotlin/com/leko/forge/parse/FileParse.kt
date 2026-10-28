package com.leko.forge.parse

import ForgeLexer
import ForgeParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import java.io.File

fun File.parseForge(): ForgeParser.RootContext {
    val input = CharStreams.fromFileName(this.absolutePath)
    val lexer = ForgeLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = ForgeParser(tokens)
    return parser.root()
}