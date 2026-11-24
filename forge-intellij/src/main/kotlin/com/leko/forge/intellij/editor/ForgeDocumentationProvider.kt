package com.leko.forge.intellij.editor

import ForgeLexer
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.util.TextRange
import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.leko.forge.ide.outline.ForgeDocs
import com.leko.forge.intellij.lexer.ForgeDocComment
import com.leko.forge.intellij.semantics.ForgeImports
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.ForgeLanguage
import com.leko.forge.intellij.hierarchy.hierarchy
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeRuleType
import com.leko.forge.intellij.psi.ForgeDeclaredName
import com.leko.forge.intellij.psi.ForgeNameElement
import com.leko.forge.intellij.semantics.ForgeFileSemantics

/**
 * The documentation of a declaration, shown on hover and with Ctrl+Q: its signature, highlighted, its doc
 * comment (`/** ... */` before it), what it is, and how it is related to other declarations.
 *
 * Doc comments are Markdown, as KDoc ([ForgeDocs]): code blocks are highlighted, `[name]` links to the
 * declaration (or the parameter) of that name. The editor can show them rendered in place (Render Doc Comments).
 */
class ForgeDocumentationProvider : AbstractDocumentationProvider() {

    // ---- doc comments rendered in the editor, as KDoc ---------------------------------------------------

    override fun collectDocComments(file: PsiFile, sink: java.util.function.Consumer<in PsiDocCommentBase>) {
        if (file.language != ForgeLanguage) return
        PsiTreeUtil.findChildrenOfType(file, ForgeDocComment::class.java).forEach { sink.accept(it) }
    }

    override fun findDocComment(file: PsiFile, range: TextRange): PsiDocCommentBase? =
        PsiTreeUtil.getParentOfType(file.findElementAt(range.startOffset), ForgeDocComment::class.java, false)?.takeIf { it.textRange == range }

    override fun generateRenderedDoc(comment: PsiDocCommentBase): String? = render(comment.text, comment)

    /** `[name]` links to the declaration it names: a parameter of the documented declaration, or one of the program. */
    override fun getDocumentationElementForLink(psiManager: PsiManager, link: String, context: PsiElement?): PsiElement? {
        val file = context?.containingFile ?: return null
        val name = link.substringAfterLast('.').substringAfterLast("::")
        val owner = (context as? ForgeDocComment)?.owner ?: context
        val declaration = generateSequence(owner) { it.parent }.firstOrNull { it.parent?.node?.elementType.let { t -> t is ForgeRuleType && t.ruleName in setOf("Root", "ModDef", "TraitDef") } }
        // the standard library only if the file does not declare it
        val candidates = sequenceOf(declaration, file) + sequence { yield(ForgeImports.fileOf(com.leko.forge.parse.STD_SOURCE, file)) }
        return candidates.filterNotNull().firstNotNullOfOrNull { scope ->
            PsiTreeUtil.findChildrenOfType(scope, ForgeDeclaredName::class.java).firstOrNull { it.name == name }
        }
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val name = element as? ForgeDeclaredName ?: return null
        val info = Info.of(name) ?: return null
        return buildString {
            append(DocumentationMarkup.DEFINITION_START)
            HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(this, name.project, ForgeLanguage, info.signature, 1f)
            append(DocumentationMarkup.DEFINITION_END)
            info.comment?.let { append(DocumentationMarkup.CONTENT_START).append(render(it, name)).append(DocumentationMarkup.CONTENT_END) }
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

    /** A doc comment as HTML: Markdown, code blocks highlighted as Forge, `[name]` a link to its declaration. */
    private fun render(comment: String, context: PsiElement): String = ForgeDocs.html(
        comment,
        code = { code ->
            buildString {
                append("<pre><code>")
                HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(this, context.project, ForgeLanguage, code, 1f)
                append("</code></pre>")
            }
        },
        reference = { name ->
            val code = "<code>${ForgeDocs.escape(name)}</code>"
            if (getDocumentationElementForLink(PsiManager.getInstance(context.project), name, context) == null) code
            else "<a href=\"${DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL}${ForgeDocs.escape(name)}\">$code</a>"
        },
    )

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

            /** The declaration [element] is in: one of the file or of a module (a module for its own name). */
            private fun topLevel(element: PsiElement): PsiElement? =
                generateSequence(element) { it.parent }.firstOrNull {
                    it.parent?.rule() == "Root" || (it.parent?.rule() == "ModDef" && it.rule() != "ModDef") ||
                        (it.rule() == "ModDef" && it === element.parent)
                }

            private fun type(name: ForgeNameElement): String? {
                val document = name.containingFile.viewProvider.document ?: return null
                val line = document.getLineNumber(name.textOffset)
                val column = name.textOffset - document.getLineStartOffset(line)
                return ForgeFileSemantics.of(name.containingFile).declarationTypes[(line + 1) to column]
            }

            fun of(name: ForgeDeclaredName): Info? {
                val decl = topLevel(name) ?: return null
                val parent = name.parent
                val kind = when (name.role) {
                    ForgeNameRole.NODE_DECLARATION -> if (decl.text.contains("(")) "node" else "interface node"
                    ForgeNameRole.TYPE_DECLARATION -> if (decl.rule() == "Enum") "enum" else "record"
                    ForgeNameRole.TYPE_PARAMETER -> "type parameter of ${header(decl)}"
                    ForgeNameRole.ENUM_CONSTANT -> "enum constant"
                    ForgeNameRole.MODULE -> "module"
                    ForgeNameRole.TRAIT -> "trait"
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
                // a member of a trait: its own signature and doc comment
                val member = generateSequence(parent) { it.parent }.takeWhile { it !== decl }.firstOrNull { it.rule() == "TraitMember" }
                if (part == null && member != null && parent === member) {
                    return Info(oneLine(member.text), "member of ${header(decl)}", docComment(member), null)
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
