package com.leko.forge.gen

/**
 * Re-indents generated C-like code by bracket depth. String literals and comments
 * are skipped; a line following a line that ends with `=` is indented once more.
 */
fun reindent(code: String, indent: String = "    "): String {
    val out = StringBuilder()
    var depth = 0
    var continuation = false
    for (raw in code.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) {
            out.appendLine()
            continue
        }
        var leadingClosers = 0
        while (leadingClosers < line.length && line[leadingClosers] in ")]}") leadingClosers++
        var delta = 0
        var minDelta = 0
        var i = 0
        var inString = false
        while (i < line.length) {
            val c = line[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
            } else {
                when {
                    c == '"' -> inString = true
                    c == '/' && i + 1 < line.length && line[i + 1] == '/' -> break
                    c == '/' && i + 1 < line.length && line[i + 1] == '*' -> {
                        val end = line.indexOf("*/", i + 2)
                        if (end < 0) break
                        i = end + 1
                    }
                    c in "([{" -> delta++
                    c in ")]}" -> {
                        delta--
                        minDelta = minOf(minDelta, delta)
                    }
                }
            }
            i++
        }
        val level = maxOf(0, depth + maxOf(minDelta, -leadingClosers) + (if (continuation) 1 else 0))
        out.append(indent.repeat(level)).appendLine(line)
        depth = maxOf(0, depth + delta)
        continuation = line.endsWith("=") && !line.startsWith("//")
    }
    return out.toString()
}
