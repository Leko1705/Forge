package com.leko.forge.intellij.semantics

import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException

/**
 * Where the lines of an old text are in a new one: the lines both have (by a line diff) move by the lines inserted
 * and deleted before them; the changed lines are gone. Lines and offsets are those of ANTLR: lines from 1.
 */
class ForgeLineMap private constructor(
    private val oldText: String,
    private val newText: String,
    /** For each old line (from 0), its new line, or -1. */
    private val lines: IntArray,
) {
    private val oldStarts = lineStarts(oldText)
    private val newStarts = lineStarts(newText)

    /** The new line of [line] (from 1), `null` if it changed. */
    fun line(line: Int): Int? = lines.getOrNull(line - 1)?.takeIf { it >= 0 }?.plus(1)

    /** The new offset of [offset] of the old text, `null` if its line changed. */
    fun offset(offset: Int): Int? {
        val line = oldStarts.indexOfLast { it <= offset }
        val newLine = lines.getOrNull(line)?.takeIf { it >= 0 } ?: return null
        return newStarts[newLine] + (offset - oldStarts[line])
    }

    companion object {
        private fun lineStarts(text: String): IntArray =
            (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()

        fun between(oldText: String, newText: String): ForgeLineMap? {
            val oldLines = oldText.split('\n').toTypedArray()
            val newLines = newText.split('\n').toTypedArray()
            val change = try {
                Diff.buildChanges(oldLines, newLines)
            } catch (e: FilesTooBigForDiffException) {
                return null
            }
            val map = IntArray(oldLines.size) { -1 }
            var oldLine = 0
            var newLine = 0
            var current = change
            while (true) {
                // the unchanged lines up to the next change
                val until = current?.line0 ?: oldLines.size
                while (oldLine < until) map[oldLine++] = newLine++
                if (current == null) break
                oldLine += current.deleted
                newLine += current.inserted
                current = current.link
            }
            return ForgeLineMap(oldText, newText, map)
        }
    }
}
