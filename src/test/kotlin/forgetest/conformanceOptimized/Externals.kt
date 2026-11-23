package forgetest.conformanceOptimized

/** The externals of conformance.forge; `str.toInt()` is Kotlin's own. */
fun Int.text(): String = toString()
fun String.shout(): String = uppercase() + "!"
