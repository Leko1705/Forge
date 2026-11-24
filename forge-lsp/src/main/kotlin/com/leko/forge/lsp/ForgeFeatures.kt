package com.leko.forge.lsp

import ForgeLexer
import ForgeParser
import com.leko.forge.ide.completion.ForgeCandidate
import com.leko.forge.ide.completion.ForgeCandidateKind
import com.leko.forge.ide.completion.ForgeCompletion
import com.leko.forge.ide.diagnostics.ForgeSeverity
import com.leko.forge.ide.lexer.ForgeEditorLexer
import com.leko.forge.ide.outline.ForgeDeclarations
import com.leko.forge.ide.outline.ForgeDeclaredName
import com.leko.forge.ide.outline.ForgeOutlineEntry
import com.leko.forge.ide.parser.ForgeAntlr
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.ide.parser.isDeclaration
import com.leko.forge.ide.parser.nameRole
import com.leko.forge.ide.semantics.ForgeResolution
import com.leko.forge.parse.STD_SOURCE
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SymbolKind
import java.io.File

/** The features of the language server, on the analysis of a document; they do not depend on lsp4j's protocol classes. */
class ForgeFeatures(private val workspace: ForgeWorkspace) {

    // ---- diagnostics -----------------------------------------------------------------------------------------

    fun diagnostics(analysis: ForgeDocumentAnalysis): List<Diagnostic> = analysis.problems.map { problem ->
        val range = problem.range?.let { analysis.positions.range(it.start, it.end) } ?: Range(Position(0, 0), Position(0, 0))
        val code = CODE.find(problem.message)?.groupValues?.get(1)
        Diagnostic(range, problem.text ?: problem.message).apply {
            severity = when (problem.severity) {
                ForgeSeverity.ERROR -> DiagnosticSeverity.Error
                ForgeSeverity.WARNING -> DiagnosticSeverity.Warning
                ForgeSeverity.INFO -> DiagnosticSeverity.Information
            }
            source = "forge"
            if (code != null) setCode(code)
        }
    }

    // ---- completion ------------------------------------------------------------------------------------------

    /**
     * The completions at [offset]: [ForgeCompletion] on the text with the dummy identifier at the caret, as
     * IntelliJ completes. Nothing in comments and strings.
     */
    fun completion(analysis: ForgeDocumentAnalysis, offset: Int): List<CompletionItem> {
        val text = analysis.text
        if (inCommentOrString(text, offset)) return emptyList()
        val withDummy = text.substring(0, offset) + ForgeCompletion.DUMMY_PREFIX + " " + text.substring(offset)
        val candidates = ForgeCompletion(withDummy, offset, analysis.document.file, workspace).candidates()
        return candidates.distinctBy { it.name to it.kind }.map(::completionItem)
    }

    private fun inCommentOrString(text: String, offset: Int): Boolean {
        val lexer = ForgeEditorLexer(text)
        while (true) {
            val type = lexer.tokenType ?: return false
            if (lexer.tokenStart < offset && offset < lexer.tokenEnd ||
                (lexer.tokenEnd == offset && type == ForgeLexer.LINE_COMMENT)) {
                return type == ForgeLexer.LINE_COMMENT || type == ForgeLexer.BLOCK_COMMENT ||
                    type == ForgeLexer.STRING || type == ForgeLexer.REGEX_STRING
            }
            if (lexer.tokenStart >= offset) return false
            lexer.advance()
        }
    }

    private fun completionItem(candidate: ForgeCandidate): CompletionItem = CompletionItem(candidate.name).apply {
        kind = completionKind(candidate.kind)
        detail = candidate.type
        if (candidate.tail != null || candidate.type != null) labelDetails = CompletionItemLabelDetails().apply {
            detail = candidate.tail
            description = candidate.type
        }
        // the order of IntelliJ's priorities: locals first, keywords last
        sortText = "${10 - priority(candidate.kind)}_${candidate.name}"
        when {
            candidate.kind == ForgeCandidateKind.KEYWORD && candidate.name !in setOf("this", "true", "false", "null") -> {
                insertText = candidate.name + " "
            }
            candidate.call -> {
                insertText = if (candidate.hasParameters) "${candidate.name}($1)$0" else "${candidate.name}()"
                insertTextFormat = InsertTextFormat.Snippet
            }
        }
    }

    private fun priority(kind: ForgeCandidateKind): Int = when (kind) {
        ForgeCandidateKind.LOCAL_VARIABLE, ForgeCandidateKind.PARAMETER -> 9
        ForgeCandidateKind.FIELD, ForgeCandidateKind.ATTRIBUTE, ForgeCandidateKind.PROPERTY, ForgeCandidateKind.ENUM_CONSTANT -> 8
        ForgeCandidateKind.METHOD, ForgeCandidateKind.PARSER_RULE -> 7
        ForgeCandidateKind.CONSTANT, ForgeCandidateKind.FUNCTION -> 5
        ForgeCandidateKind.KEYWORD -> 1
        else -> 3
    }

    private fun completionKind(kind: ForgeCandidateKind): CompletionItemKind = when (kind) {
        ForgeCandidateKind.KEYWORD -> CompletionItemKind.Keyword
        ForgeCandidateKind.NODE, ForgeCandidateKind.PARSER -> CompletionItemKind.Class
        ForgeCandidateKind.INTERFACE_NODE -> CompletionItemKind.Interface
        ForgeCandidateKind.RECORD -> CompletionItemKind.Struct
        ForgeCandidateKind.ENUM -> CompletionItemKind.Enum
        ForgeCandidateKind.BUILTIN_TYPE -> CompletionItemKind.TypeParameter
        ForgeCandidateKind.ENUM_CONSTANT -> CompletionItemKind.EnumMember
        ForgeCandidateKind.FIELD -> CompletionItemKind.Field
        ForgeCandidateKind.ATTRIBUTE, ForgeCandidateKind.PROPERTY -> CompletionItemKind.Property
        ForgeCandidateKind.METHOD, ForgeCandidateKind.PARSER_RULE -> CompletionItemKind.Method
        ForgeCandidateKind.FUNCTION -> CompletionItemKind.Function
        ForgeCandidateKind.CONSTANT, ForgeCandidateKind.PORT -> CompletionItemKind.Constant
        ForgeCandidateKind.PARAMETER, ForgeCandidateKind.LOCAL_VARIABLE -> CompletionItemKind.Variable
        ForgeCandidateKind.GRAPH -> CompletionItemKind.Module
        ForgeCandidateKind.ANNOTATION -> CompletionItemKind.Event
    }

    // ---- what a name is --------------------------------------------------------------------------------------

    /** Where a name is declared: [source] as [ForgeResolution.source], the ANTLR [line] (from 1) and [column]. */
    private data class Target(val source: String?, val line: Int, val column: Int, val name: String, val role: ForgeNameRole?)

    /** What the name at [offset] refers to: its resolution, or the declaration it is. */
    private fun targetAt(analysis: ForgeDocumentAnalysis, offset: Int): Target? {
        val token = analysis.identifierAt(offset) ?: return null
        analysis.semantics.resolutions[token.startIndex]?.let { return Target(it.source, it.line, it.column, it.name, it.role) }
        val declared = analysis.declarations.names.firstOrNull { it.start == token.startIndex } ?: return null
        return Target(null, declared.token.line, declared.token.charPositionInLine, declared.name, declared.role)
    }

    private fun location(analysis: ForgeDocumentAnalysis, target: Target): Location? {
        val uri = workspace.uriOfSource(target.source, analysis.document) ?: return null
        val start = Position(target.line - 1, target.column)
        return Location(uri, Range(start, Position(start.line, start.character + target.name.length)))
    }

    // ---- go to definition ------------------------------------------------------------------------------------

    fun definition(analysis: ForgeDocumentAnalysis, offset: Int): List<Location> {
        importAt(analysis, offset)?.let { file -> return listOf(Location(uriOf(file), Range(Position(0, 0), Position(0, 0)))) }
        val token = analysis.identifierAt(offset) ?: return emptyList()
        val resolution = analysis.semantics.resolutions[token.startIndex] ?: return emptyList()
        return listOfNotNull(location(analysis, Target(resolution.source, resolution.line, resolution.column, resolution.name, resolution.role)))
    }

    /** The file of the `import` whose path is at [offset]. */
    private fun importAt(analysis: ForgeDocumentAnalysis, offset: Int): File? {
        val decl = analysis.tree.importDecl().firstOrNull { it.path != null && offset in it.path.startIndex..it.path.stopIndex } ?: return null
        val path = decl.path.text.removeSurrounding("\"")
        if (path == "std") return workspace.stdFile
        val base = analysis.document.file?.parentFile ?: return null
        return File(base, path).takeIf { it.isFile }
    }

    // ---- find references -------------------------------------------------------------------------------------

    /**
     * The uses of what the name at [offset] refers to, in the open documents (the uses in other files are only
     * known to their own analysis), and its declaration if [includeDeclaration].
     */
    fun references(analysis: ForgeDocumentAnalysis, offset: Int, includeDeclaration: Boolean): List<Location> {
        val target = targetAt(analysis, offset) ?: return emptyList()
        val targetFile = workspace.fileOfSource(target.source, analysis.document)
        fun sameTarget(r: ForgeResolution, from: ForgeDocumentAnalysis): Boolean {
            if (r.line != target.line || r.column != target.column) return false
            if (r.source == null && target.source == null && from.document === analysis.document) return true
            if (r.source == STD_SOURCE || target.source == STD_SOURCE) return r.source == target.source
            val file = workspace.fileOfSource(r.source, from.document) ?: return false
            return targetFile != null && ForgeWorkspace.canonical(file) == ForgeWorkspace.canonical(targetFile)
        }
        val uses = workspace.openDocuments.mapNotNull { workspace.analysis(it.uri) }.flatMap { other ->
            other.semantics.resolutions.filter { (_, r) -> sameTarget(r, other) }.map { (start, r) ->
                Location(other.document.uri, other.positions.range(start, start + r.name.length))
            }
        }
        val declaration = if (includeDeclaration) listOfNotNull(location(analysis, target)) else emptyList()
        return (declaration + uses).distinctBy { it.uri to it.range }
    }

    // ---- hover -----------------------------------------------------------------------------------------------

    /** The documentation of the declaration the name at [offset] declares or refers to. */
    fun hover(analysis: ForgeDocumentAnalysis, offset: Int): Hover? {
        val token = analysis.identifierAt(offset) ?: return null
        val target = targetAt(analysis, offset) ?: return null
        val inThisFile = target.source == null
        val declarations = if (inThisFile) analysis.declarations else {
            val text = workspace.textOfSource(target.source, analysis.document) ?: return null
            ForgeDeclarations(text, ForgeAntlr.parse(text).first)
        }
        val declared = declarations.nameAt(target.line, target.column) ?: return null
        val type = if (inThisFile) analysis.semantics.declarationTypes[target.line to target.column] else null
        val info = declarations.info(declared, type) ?: return null
        val markdown = buildString {
            append("```forge\n").append(info.signature)
            info.type?.let { append(": ").append(it) }
            append("\n```\n\n")
            info.comment?.let { append(renderComment(it)).append("\n\n") }
            append("*").append(info.kind).append("*")
            if (inThisFile) analysis.semantics.hierarchy[declared.start]?.let { h ->
                if (h.supers.isNotEmpty()) append("\n\n").append(if (h.supers.all { it.abstract }) "Implements " else "Overrides ").append(codes(h.supers.map { it.label }))
                if (h.subs.isNotEmpty()) append("\n\n").append(if (h.abstract) "Implemented in " else "Overridden in ").append(codes(h.subs.map { it.label }))
            }
            if (!inThisFile) {
                val file = workspace.fileOfSource(target.source, analysis.document)
                append("\n\nin `").append(if (target.source == STD_SOURCE) STD_SOURCE else file?.name ?: target.source).append("`")
            }
        }
        val range = analysis.positions.range(token.startIndex, token.stopIndex + 1)
        return Hover(MarkupContent(MarkupKind.MARKDOWN, markdown), range)
    }

    private fun codes(labels: List<String>) = labels.joinToString(", ") { "`$it`" }

    /** A doc comment as Markdown: its text, without the `/**`, `*/` and the `*` at the start of its lines. */
    private fun renderComment(comment: String): String =
        comment.removePrefix("/**").removeSuffix("*/").lines()
            .joinToString("\n") { it.trim().removePrefix("*").removePrefix(" ") }.trim()
            .replace(Regex("\\[([A-Za-z_][\\w.]*)]"), "`$1`")

    // ---- document symbols ------------------------------------------------------------------------------------

    fun documentSymbols(analysis: ForgeDocumentAnalysis): List<DocumentSymbol> =
        analysis.declarations.outline().map { symbol(analysis, it, analysis.declarations) }

    private fun symbol(analysis: ForgeDocumentAnalysis, entry: ForgeOutlineEntry, declarations: ForgeDeclarations): DocumentSymbol {
        val name = entry.name
        val positions = analysis.positions
        return DocumentSymbol(
            qualifiedName(name), symbolKind(entry, declarations),
            positions.range(entry.start, entry.end), positions.range(name.start, name.end),
            entry.label,
            entry.children.map { symbol(analysis, it, declarations) },
        )
    }

    /** `Expr.type` for the attribute `type` of `syn Expr.type`, `P.expr` for the rule `rule P.expr`, otherwise the name. */
    private fun qualifiedName(name: ForgeDeclaredName): String = when (val c = name.context) {
        is ForgeParser.SynDefContext -> "${c.node?.text}.${name.name}"
        is ForgeParser.CircularDefContext -> "${c.node?.text}.${name.name}"
        is ForgeParser.ParseRuleDefContext -> "${c.parserName?.text}.${name.name}"
        else -> name.name
    }

    private fun symbolKind(entry: ForgeOutlineEntry, declarations: ForgeDeclarations): SymbolKind = when (entry.name.role) {
        ForgeNameRole.NODE_DECLARATION -> if (entry.isInterface) SymbolKind.Interface else SymbolKind.Class
        ForgeNameRole.TYPE_DECLARATION -> if (entry.isEnum) SymbolKind.Enum else SymbolKind.Struct
        ForgeNameRole.PARSER -> SymbolKind.Class
        ForgeNameRole.FUNCTION_DECLARATION -> SymbolKind.Function
        ForgeNameRole.PROPERTY, ForgeNameRole.ATTRIBUTE -> SymbolKind.Property
        ForgeNameRole.CONSTANT, ForgeNameRole.PORT -> SymbolKind.Constant
        ForgeNameRole.PARSER_RULE -> SymbolKind.Method
        ForgeNameRole.ENUM_CONSTANT -> SymbolKind.EnumMember
        ForgeNameRole.FIELD -> SymbolKind.Field
        ForgeNameRole.GRAPH -> SymbolKind.Namespace
        ForgeNameRole.PARAMETER -> if (declarations.isNodeChild(entry.name)) SymbolKind.Field else SymbolKind.Variable
        else -> SymbolKind.Variable
    }

    // ---- semantic tokens -------------------------------------------------------------------------------------

    /**
     * The names, colored as in IntelliJ: by what the compiler resolved them to (so `this.type` is an attribute if
     * `type` is a syn attribute, but a field if it is a field of the node), or else by their role in the grammar.
     */
    fun semanticTokens(analysis: ForgeDocumentAnalysis): SemanticTokens {
        val resolutions = analysis.semantics.resolutions
        val data = mutableListOf<Int>()
        var lastLine = 0
        var lastStart = 0
        fun visit(node: ParseTree) {
            if (node is TerminalNode) {
                val context = node.parent as? ParserRuleContext ?: return
                val token = node.symbol
                if (token.startIndex < 0 || token.tokenIndex < 0) return
                val grammarRole = nameRole(context, token) ?: return
                val role = resolutions[token.startIndex]?.role ?: grammarRole
                val type = TOKEN_TYPES[role] ?: return
                var modifiers = 0
                if (isDeclaration(context, token)) modifiers = modifiers or (1 shl MODIFIERS.indexOf("declaration"))
                if (role == ForgeNameRole.CONSTANT) modifiers = modifiers or (1 shl MODIFIERS.indexOf("readonly"))
                if (role == ForgeNameRole.BUILTIN_TYPE) modifiers = modifiers or (1 shl MODIFIERS.indexOf("defaultLibrary"))
                val position = analysis.positions.position(token.startIndex)
                val deltaLine = position.line - lastLine
                val deltaStart = if (deltaLine == 0) position.character - lastStart else position.character
                data += listOf(deltaLine, deltaStart, token.stopIndex + 1 - token.startIndex, TYPES.indexOf(type), modifiers)
                lastLine = position.line
                lastStart = position.character
                return
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(analysis.tree)
        return SemanticTokens(data)
    }

    // ---- folding ---------------------------------------------------------------------------------------------

    /** The declarations and block comments that span more than one line. */
    fun foldingRanges(analysis: ForgeDocumentAnalysis): List<FoldingRange> {
        val positions = analysis.positions
        val ranges = mutableListOf<FoldingRange>()
        for (decl in analysis.tree.children.orEmpty().filterIsInstance<ParserRuleContext>()) {
            val start = decl.start ?: continue
            val stop = decl.stop ?: continue
            if (stop.stopIndex < start.startIndex) continue
            val first = positions.position(start.startIndex).line
            val last = positions.position(stop.stopIndex).line
            if (last > first) ranges += FoldingRange(first, last)
        }
        val lexer = ForgeEditorLexer(analysis.text)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type == ForgeLexer.BLOCK_COMMENT) {
                val first = positions.position(lexer.tokenStart).line
                val last = positions.position(lexer.tokenEnd - 1).line
                if (last > first) ranges += FoldingRange(first, last).apply { kind = FoldingRangeKind.Comment }
            }
            lexer.advance()
        }
        return ranges
    }

    companion object {
        /** The code at the end of a message of the compiler: `[E009]`. */
        private val CODE = Regex("\\[([A-Z]\\d+)]$")

        /**
         * The token types: LSP's, and some of Forge (attribute, port, graph, parser, rule), for which the VS Code
         * extension declares a standard type they fall back to.
         */
        val TYPES = listOf(
            "class", "struct", "type", "typeParameter", "enumMember", "function", "method", "property", "variable",
            "parameter", "keyword", "decorator", "attribute", "port", "graph", "parser", "rule",
        )
        val MODIFIERS = listOf("declaration", "readonly", "defaultLibrary")
        val LEGEND = SemanticTokensLegend(TYPES, MODIFIERS)

        private val TOKEN_TYPES: Map<ForgeNameRole, String> = mapOf(
            ForgeNameRole.NODE_DECLARATION to "class",
            ForgeNameRole.TYPE_DECLARATION to "struct",
            ForgeNameRole.TYPE_REFERENCE to "type",
            ForgeNameRole.BUILTIN_TYPE to "type",
            ForgeNameRole.TYPE_PARAMETER to "typeParameter",
            ForgeNameRole.ENUM_CONSTANT to "enumMember",
            ForgeNameRole.FUNCTION_DECLARATION to "function",
            ForgeNameRole.FUNCTION_CALL to "function",
            ForgeNameRole.METHOD_CALL to "method",
            ForgeNameRole.PROPERTY to "property",
            ForgeNameRole.CONSTANT to "variable",
            ForgeNameRole.FIELD to "property",
            ForgeNameRole.PARAMETER to "parameter",
            ForgeNameRole.LOCAL_VARIABLE to "variable",
            ForgeNameRole.ATTRIBUTE to "attribute",
            ForgeNameRole.PORT to "port",
            ForgeNameRole.GRAPH to "graph",
            ForgeNameRole.PARSER to "parser",
            ForgeNameRole.PARSER_RULE to "rule",
            ForgeNameRole.ANNOTATION to "decorator",
            ForgeNameRole.SOFT_KEYWORD to "keyword",
        )
    }
}
