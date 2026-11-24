package com.leko.forge.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent

/**
 * Offsets in [text] and LSP positions: lines from 0, columns in UTF-16 code units (LSP's default encoding, and
 * the one of Kotlin strings and of the compiler's ANTLRInputStream), so a column is an offset in its line.
 */
class ForgeText(val text: String) {
    private val lineStarts: IntArray = (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()

    val lineCount: Int get() = lineStarts.size

    /** The offset of [position]; a position after the end of its line is the end of the line. */
    fun offset(position: Position): Int {
        if (position.line < 0) return 0
        if (position.line >= lineStarts.size) return text.length
        val start = lineStarts[position.line]
        val end = if (position.line + 1 < lineStarts.size) lineStarts[position.line + 1] - 1 else text.length
        return (start + position.character.coerceAtLeast(0)).coerceAtMost(end)
    }

    /** The offset of an ANTLR position: [line] from 1, [column] from 0. */
    fun offset(line: Int, column: Int): Int = offset(Position(line - 1, column))

    fun position(offset: Int): Position {
        val o = offset.coerceIn(0, text.length)
        val found = lineStarts.binarySearch(o)
        val line = if (found >= 0) found else -found - 2
        return Position(line, o - lineStarts[line])
    }

    fun range(start: Int, end: Int): Range = Range(position(start), position(end.coerceAtLeast(start)))

    /** [text] with [change]: of a range, or the whole text if it has none. */
    fun apply(change: TextDocumentContentChangeEvent): String {
        val range = change.range ?: return change.text
        val start = offset(range.start)
        val end = offset(range.end).coerceAtLeast(start)
        return text.substring(0, start) + change.text + text.substring(end)
    }
}
