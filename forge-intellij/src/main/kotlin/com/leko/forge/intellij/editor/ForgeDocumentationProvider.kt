package com.leko.forge.intellij.editor

import ForgeLexer
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.leko.forge.intellij.ForgeLanguage
import com.leko.forge.intellij.hierarchy.hierarchy
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeNameRole
import com.leko.forge.intellij.parser.ForgeRuleType
import com.leko.forge.intellij.psi.ForgeDeclaredName
import com.leko.forge.intellij.psi.ForgeNameElement
import com.leko.forge.intellij.semantics.ForgeSemantics

/**
 * The documentation of a declaration, shown on hover and with Ctrl+Q: its signature, highlighted, its doc
 * comment (`/** ... */` before it), what it is, and how it is related to other declarations.
 */
class ForgeDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val name = element as? ForgeDeclaredName ?: return null
        val info = Info.of(name) ?: return null
        return buildString {
            append(DocumentationMarkup.DEFINITION_START)
            HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(this, name.project, ForgeLanguage, info.signature, 1f)
            append(DocumentationMarkup.DEFINITION_END)
            info.comment?.let { append(DocumentationMarkup.CONTENT_START).append(render(it)).append(DocumentationMarkup.CONTENT_END) }
            append(DocumentationMarkup.SECTIONS_START)
            section("Kind", StringUtil.escapeXmlEntities(info.kind))
            info.type?.let { section("Type", "<code>${StringUtil.escapeXmlEntities(it)}</code>") }
            name.hierarchy?.let { h ->
                if (h.supers.isNotEmpty()) section(if (h.supers.all { it.abstract }) "Implements" else "Overrides", codes(h.supers.map { it.label }))
                if (h.subs.isNotEmpty()) section(if (h.abstract) "Implemented in" else "Overridden in", codes(h.subs.map { it.label }))
            }
            if (originalElement?.containingFile != name.containingFile) section("File", StringUtil.escapeXmlEntities(name.containingFile.name))
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val name = element as? ForgeDeclaredName ?: return null
        val info = Info.of(name) ?: return null
        return StringUtil.escapeXmlEntities(info.signature) + (info.type?.let { ": " + StringUtil.escapeXmlEntities(it) } ?: "")
    }

    private fun StringBuilder.section(title: String, html: String) {
        append(DocumentationMarkup.SECTION_HEADER_START).append(title).append(":").append(DocumentationMarkup.SECTION_SEPARATOR)
            .append(html).append(DocumentationMarkup.SECTION_END)
    }

    private fun codes(labels: List<String>) = labels.joinToString(", ") { "<code>${StringUtil.escapeXmlEntities(it)}</code>" }

    /** A doc comment as HTML: `code` and [references] in code font, a blank line between paragraphs. */
    private fun render(comment: String): String {
        val text = comment.removePrefix("/**").removeSuffix("*/").lines()
            .joinToString("\n") { it.trim().removePrefix("*").removePrefix(" ") }.trim()
        return text.split(Regex("\n\\s*\n")).joinToString("") { paragraph ->
            "<p>" + StringUtil.escapeXmlEntities(paragraph)
                .replace(Regex("`([^`]+)`"), "<code>$1</code>")
                .replace(Regex("\\[([A-Za-z_][\\w.]*)]"), "<code>$1</code>") + "</p>"
        }
    }

    /** What the documentation shows of a declaration. */
    private class Info(val signature: String, val kind: String, val comment: String?, val type: String?) {
        companion object {
            private val assign = ForgeTokenTypes.of(ForgeLexer.ASSIGN)
            private val arrow = ForgeTokenTypes.of(ForgeLexer.ARROW)
            private val lbrace = ForgeTokenTypes.of(ForgeLexer.LBRACE)

            private fun PsiElement.rule(): String? = (node.elementType as? ForgeRuleType)?.ruleName

            private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim()

            /** The text of a declaration up to its body. */
            private fun header(decl: PsiElement): String {
                val stop = decl.node.getChildren(null).firstOrNull { child ->
                    when (decl.rule()) {
                        "ParseRuleDef" -> child.elementType == arrow
                        "Record", "Enum" -> false
                        "InhImpl", "FlowDef" -> child.elementType == lbrace
                        "ConstDef" -> false
                        else -> child.elementType == assign
                    }
                }
                val end = stop?.startOffset ?: decl.textRange.endOffset
                val text = oneLine(decl.containingFile.text.substring(decl.textRange.startOffset, end))
                return if (text.length > 160) text.take(157) + "..." else text
            }

            /** The doc comment right before [decl], if any. */
            private fun docComment(decl: PsiElement): String? {
                var previous = decl.prevSibling
                while (previous is PsiWhiteSpace) previous = previous.prevSibling
                return (previous as? PsiComment)?.text?.takeIf { it.startsWith("/**") }
            }

            /** The declaration of the whole file [element] is in: a child of the Root rule. */
            private fun topLevel(element: PsiElement): PsiElement? =
                generateSequence(element) { it.parent }.firstOrNull { it.parent?.rule() == "Root" }

            private fun type(name: ForgeNameElement): String? {
                val document = name.containingFile.viewProvider.document ?: return null
                val line = document.getLineNumber(name.textOffset)
                val column = name.textOffset - document.getLineStartOffset(line)
                return ForgeSemantics.of(name.containingFile).declarationTypes[(line + 1) to column]
            }

            fun of(name: ForgeDeclaredName): Info? {
                val decl = topLevel(name) ?: return null
                val parent = name.parent
                val kind = when (name.role) {
                    ForgeNameRole.NODE_DECLARATION -> if (decl.text.contains("(")) "node" else "interface node"
                    ForgeNameRole.TYPE_DECLARATION -> if (decl.rule() == "Enum") "enum" else "record"
                    ForgeNameRole.TYPE_PARAMETER -> "type parameter of ${header(decl)}"
                    ForgeNameRole.ENUM_CONSTANT -> "enum constant"
                    ForgeNameRole.FUNCTION_DECLARATION -> if (decl.text.startsWith("ext")) "external function" else "function"
                    ForgeNameRole.PROPERTY -> "property (computed at each use)"
                    ForgeNameRole.CONSTANT -> "constant"
                    ForgeNameRole.FIELD -> "field"
                    ForgeNameRole.PARAMETER -> if (decl.rule() == "NodeDef") "child" else "parameter"
                    ForgeNameRole.LOCAL_VARIABLE -> "variable"
                    ForgeNameRole.ATTRIBUTE -> when (decl.rule()) {
                        "SynDef" -> "synthesized attribute"
                        "InhDef" -> "inherited attribute"
                        "CircularDef" -> "circular attribute"
                        "CollDef", "Coll" -> "collection attribute"
                        else -> "attribute"
                    }
                    ForgeNameRole.PORT -> "port"
                    ForgeNameRole.GRAPH -> "graph"
                    ForgeNameRole.PARSER -> "parser"
                    ForgeNameRole.PARSER_RULE -> "parser rule"
                    else -> "name"
                }
                val owner = header(decl)
                // a part of a declaration: a parameter, a field, an enum constant, a local, ...
                val part = generateSequence(parent) { it.parent }.takeWhile { it !== decl }.firstOrNull {
                    it.rule() in setOf("Parameter", "RecordField", "EnumItem")
                }
                return when {
                    part != null -> Info(oneLine(part.text), "$kind of $owner", null, null)
                    parent === decl -> Info(header(decl), kind, docComment(decl), null)
                    else -> Info(name.text, "$kind in $owner", null, type(name))
                }
            }
        }
    }
}
