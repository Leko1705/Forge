package com.leko.forge.utils

/** The value of a Forge string literal (including its quotes). */
fun unescapeStringLiteral(literal: String): String {
    val body = literal.substring(1, literal.length - 1)
    val sb = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            sb.append(
                when (val e = body[i + 1]) {
                    'b' -> '\b'
                    't' -> '\t'
                    'n' -> '\n'
                    'f' -> '\u000C'
                    'r' -> '\r'
                    else -> e
                }
            )
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private const val REGEX_META = "\\^$.|?*+()[]{}"

/**
 * If [regex] matches exactly one fixed text (it contains no unescaped meta
 * characters and only escapes of non-alphanumeric characters), that text.
 */
fun literalOfRegex(regex: String): String? {
    val sb = StringBuilder()
    var i = 0
    while (i < regex.length) {
        val c = regex[i]
        if (c == '\\') {
            if (i + 1 >= regex.length) return null
            val e = regex[i + 1]
            if (e.isLetterOrDigit()) return null
            sb.append(e)
            i += 2
        } else {
            if (c in REGEX_META) return null
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/** Text of a Kotlin string literal with the value [value]. */
fun kotlinStringLiteral(value: String): String {
    val sb = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '$' -> sb.append("\\$")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            else -> if (c < ' ' || c.code > 126) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}
