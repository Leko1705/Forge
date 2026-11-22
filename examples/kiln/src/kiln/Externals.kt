package kiln

// The `external fun`s of kiln.forge and jvm.forge. `str.toInt()` is Kotlin's own.

/** The bytes of a CONSTANT_Utf8 entry. Kiln strings have no NUL characters or surrogate pairs, so this is UTF-8. */
fun String.utf8(): List<Int> = toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }

fun Int.str(): String = toString()

/** The value of a string literal: without its quotes, with `\n`, `\t`, `\"` and `\\` replaced. */
fun String.unquote(): String {
    val sb = StringBuilder()
    var i = 1
    while (i < length - 1) {
        val c = this[i]
        if (c == '\\' && i + 1 < length - 1) {
            i++
            sb.append(when (this[i]) { 'n' -> '\n'; 't' -> '\t'; else -> this[i] })
        } else {
            sb.append(c)
        }
        i++
    }
    return sb.toString()
}
