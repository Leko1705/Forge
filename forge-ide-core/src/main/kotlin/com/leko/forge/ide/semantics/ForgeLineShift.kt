package com.leko.forge.ide.semantics

/**
 * Where the lines of an old text are in a new one, for an edit in one place: the lines before the first changed
 * line stay, the lines after the last changed line move by the lines inserted or deleted; the lines between are
 * gone. It needs no diff, and it is exact for what typing does: changes in one place at a time.
 */
class ForgeLineShift private constructor(
    private val oldStarts: IntArray,
    private val newStarts: IntArray,
    /** For each old line (from 0), its new line, or -1. */
    private val lines: IntArray,
) : ForgeLineMapping {

    override fun line(line: Int): Int? = lines.getOrNull(line - 1)?.takeIf { it >= 0 }?.plus(1)

    override fun offset(offset: Int): Int? {
        val line = oldStarts.indexOfLast { it <= offset }
        val newLine = lines.getOrNull(line)?.takeIf { it >= 0 } ?: return null
        return newStarts[newLine] + (offset - oldStarts[line])
    }

    companion object {
        private fun lineStarts(text: String): IntArray =
            (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()

        fun between(oldText: String, newText: String): ForgeLineShift {
            val oldLines = oldText.split('\n')
            val newLines = newText.split('\n')
            val common = minOf(oldLines.size, newLines.size)
            var prefix = 0
            while (prefix < common && oldLines[prefix] == newLines[prefix]) prefix++
            var suffix = 0
            while (suffix < common - prefix && oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]) suffix++
            val map = IntArray(oldLines.size) { -1 }
            for (i in 0 until prefix) map[i] = i
            for (i in 0 until suffix) map[oldLines.size - 1 - i] = newLines.size - 1 - i
            return ForgeLineShift(lineStarts(oldText), lineStarts(newText), map)
        }
    }
}
