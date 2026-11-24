package com.leko.forge.ide.outline

/**
 * Doc comments (`/** ... */` before a declaration) as HTML. Their text is Markdown, as in KDoc: paragraphs
 * separated by blank lines, lists (`- item`), code blocks in ```, `code`, **strong**, *emphasis*, [links](url),
 * and `[name]` for a declaration or a parameter. Lines may start with a `*`, which is not part of the text.
 */
object ForgeDocs {

    /** The text of a doc comment: without `/**`, `*/` and the `*` at the start of its lines. */
    fun text(comment: String): String {
        val body = comment.trim().removePrefix("/**").removeSuffix("*/")
        val lines = body.lines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("*")) trimmed.removePrefix("*").removePrefix(" ") else line.trim()
        }
        return lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }

    /**
     * The doc comment [comment] as HTML. [code] makes the HTML of a code block (e.g. highlighted), [reference] the
     * HTML of a `[name]` (e.g. a link to its declaration).
     */
    fun html(
        comment: String,
        code: (String) -> String = { "<pre><code>${escape(it)}</code></pre>" },
        reference: (String) -> String = { "<code>${escape(it)}</code>" },
    ): String {
        val out = StringBuilder()
        val paragraph = mutableListOf<String>()
        val list = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) out.append("<p>").append(inline(paragraph.joinToString(" "), reference)).append("</p>")
            paragraph.clear()
        }
        fun flushList() {
            if (list.isNotEmpty()) out.append("<ul>").append(list.joinToString("") { "<li>${inline(it, reference)}</li>" }).append("</ul>")
            list.clear()
        }
        val lines = text(comment).lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph(); flushList()
                    val block = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) block += lines[i++]
                    out.append(code(block.joinToString("\n").trimIndent()))
                }
                trimmed.isEmpty() -> { flushParagraph(); flushList() }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flushParagraph()
                    list += trimmed.substring(2)
                }
                // a line that continues an item of a list is indented
                list.isNotEmpty() && line.startsWith(" ") -> list[list.size - 1] = list.last() + " " + trimmed
                else -> {
                    flushList()
                    paragraph += trimmed
                }
            }
            i++
        }
        flushParagraph(); flushList()
        return out.toString()
    }

    private val inlinePattern = Regex(
        "`([^`]+)`" +                                  // code
            "|\\*\\*([^*]+)\\*\\*" +                   // strong
            "|(?<![\\w*])\\*([^*\\s][^*]*)\\*(?![\\w*])" + // emphasis
            "|\\[([^\\]]+)]\\(([^)\\s]+)\\)" +         // a link
            "|\\[([A-Za-z_][\\w.:]*)]",                // a declaration or a parameter
    )

    private fun inline(text: String, reference: (String) -> String): String {
        val out = StringBuilder()
        var last = 0
        for (m in inlinePattern.findAll(text)) {
            out.append(escape(text.substring(last, m.range.first)))
            val g = m.groupValues
            out.append(when {
                g[1].isNotEmpty() -> "<code>${escape(g[1])}</code>"
                g[2].isNotEmpty() -> "<b>${inline(g[2], reference)}</b>"
                g[3].isNotEmpty() -> "<i>${inline(g[3], reference)}</i>"
                g[4].isNotEmpty() -> "<a href=\"${escape(g[5])}\">${inline(g[4], reference)}</a>"
                else -> reference(g[6])
            })
            last = m.range.last + 1
        }
        out.append(escape(text.substring(last)))
        return out.toString()
    }

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
