package com.leko.forge.model

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode

data class SourceSpan(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int
) {
    override fun toString(): String = "$startLine:$startCol"
}

val ParserRuleContext.span: SourceSpan get() = SourceSpan(
    this.start.line, this.start.charPositionInLine,
    this.stop?.line ?: this.start.line,
    this.stop?.charPositionInLine ?: this.start.charPositionInLine
)

val Token.span: SourceSpan get() = SourceSpan(
    this.line, this.charPositionInLine,
    this.line,
    this.charPositionInLine + (this.text?.length ?: 0)
)

val TerminalNode.span: SourceSpan get() = this.symbol.span

operator fun SourceSpan.plus(other: SourceSpan): SourceSpan =
    SourceSpan(
        startLine = minOf(startLine, other.startLine),
        startCol = minOf(startCol, other.startCol),
        endLine = maxOf(endLine, other.endLine),
        endCol = maxOf(endCol, other.endCol)
    )
