package com.leko.forge.parse

import ForgeLexer
import ForgeParser
import com.leko.forge.analysis.parseError
import com.leko.forge.model.SourceSpan
import com.leko.forge.util.Log
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import java.io.File

private class LogErrorListener(private val log: Log, private val file: String) : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?,
    ) {
        log.diag(parseError(SourceSpan(line, charPositionInLine, line, charPositionInLine, file), msg))
    }
}

fun File.parseForge(log: Log): ForgeParser.RootContext {
    val input = CharStreams.fromFileName(this.path)
    val lexer = ForgeLexer(input)
    val listener = LogErrorListener(log, this.path)
    lexer.removeErrorListeners()
    lexer.addErrorListener(listener)
    val tokens = CommonTokenStream(lexer)
    val parser = ForgeParser(tokens)
    parser.removeErrorListeners()
    parser.addErrorListener(listener)
    return parser.root()
}

/**
 * Merges the declarations of several files into one compilation unit, so that a
 * language can be split into (and extended by) several files.
 */
fun mergeRoots(roots: List<ForgeParser.RootContext>): ForgeParser.RootContext {
    if (roots.size == 1) return roots.first()
    val merged = ForgeParser.RootContext(null, 0)
    for (root in roots) {
        for (child in root.children ?: emptyList()) {
            if (child is TerminalNodeImpl) continue // EOF
            merged.addAnyChild(child)
        }
    }
    merged.start = roots.first().start
    merged.stop = roots.last().stop
    return merged
}
