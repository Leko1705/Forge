package com.leko.forge.lsp

import ForgeLexer
import ForgeParser
import com.leko.forge.ide.ForgeHost
import com.leko.forge.ide.diagnostics.ForgeDiagnostics
import com.leko.forge.ide.diagnostics.ForgeProblem
import com.leko.forge.ide.diagnostics.ForgeSeverity
import com.leko.forge.ide.diagnostics.ForgeSource
import com.leko.forge.ide.diagnostics.ForgeTextRange
import com.leko.forge.ide.lexer.ForgeEditorLexer
import com.leko.forge.ide.outline.ForgeDeclarations
import com.leko.forge.ide.parser.ForgeAntlr
import com.leko.forge.ide.semantics.ForgeLineShift
import com.leko.forge.ide.semantics.ForgeSemantics
import com.leko.forge.parse.STD_SOURCE
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.Token
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** A document the editor has open: its [text] at [version]. */
class ForgeDocument(val uri: String, val version: Int, val text: String) {
    /** The file of the document, `null` if it is not a file (e.g. an unsaved, untitled one). */
    val file: File? = fileOf(uri)
}

/** The file of a `file:` URI. */
fun fileOf(uri: String): File? = runCatching {
    val parsed = URI(uri)
    if (parsed.scheme == "file") File(parsed).absoluteFile else null
}.getOrNull()

/** The URI of [file], as editors write them (`file:///...`). */
fun uriOf(file: File): String = file.absoluteFile.toPath().toUri().toString()

/**
 * The documents the editor has open and their analyses. As the [ForgeHost] of the analysis, it reads imported
 * files from their editors if they are open (they may not be saved), from disk otherwise.
 */
class ForgeWorkspace : ForgeHost {
    private val documents = ConcurrentHashMap<String, ForgeDocument>()

    /** Counts the changes to the program: an analysis depends on the other files too, so any change invalidates all. */
    @Volatile private var generation = 0L
    private val analyses = ConcurrentHashMap<String, Pair<Long, ForgeDocumentAnalysis>>()

    /** The last semantics of a document that could be analyzed, with its text. */
    internal val lastGood = ConcurrentHashMap<String, Pair<String, ForgeSemantics>>()

    /** Where problems of the analysis go, besides the server's log. */
    @Volatile var onLog: ((Level, String) -> Unit)? = null

    fun open(uri: String, version: Int, text: String) {
        documents[uri] = ForgeDocument(uri, version, text)
        generation++
    }

    fun change(uri: String, version: Int, text: String) {
        documents[uri] = ForgeDocument(uri, version, text)
        generation++
    }

    fun close(uri: String) {
        documents.remove(uri)
        analyses.remove(uri)
        lastGood.remove(uri)
        generation++
    }

    /** Files changed outside of the editors (e.g. on disk). */
    fun filesChanged() {
        generation++
    }

    fun document(uri: String): ForgeDocument? = documents[uri]

    val openDocuments: Collection<ForgeDocument> get() = documents.values

    /** The open document of [file], if it is open. */
    fun documentOf(file: File): ForgeDocument? {
        val path = canonical(file)
        return documents.values.firstOrNull { d -> d.file?.let(::canonical) == path }
    }

    /** The analysis of the open document [uri], computed once for each version of the program. */
    fun analysis(uri: String): ForgeDocumentAnalysis? {
        val document = documents[uri] ?: return null
        val current = generation
        analyses[uri]?.let { (gen, analysis) -> if (gen == current && analysis.document === document) return analysis }
        return ForgeDocumentAnalysis(document, this).also { analyses[uri] = current to it }
    }

    // ---- ForgeHost -------------------------------------------------------------------------------------------

    override fun read(file: File): CharStream {
        val text = documentOf(file)?.text ?: file.readText()
        return ANTLRInputStream(text).apply { name = file.absolutePath }
    }

    override fun log(level: Level, message: String, e: Throwable?) {
        LOG.log(level, message, e)
        if (level.intValue() >= Level.WARNING.intValue()) onLog?.invoke(level, if (e != null) "$message: $e" else message)
    }

    // ---- the files of declarations ---------------------------------------------------------------------------

    /** The standard library, written to a file once, so that editors can show it. */
    val stdFile: File by lazy {
        val dir = kotlin.io.path.createTempDirectory("forge-std").toFile()
        File(dir, STD_SOURCE).apply {
            writeBytes(ForgeWorkspace::class.java.getResourceAsStream("/forge/std.forge")!!.readBytes())
            setReadOnly()
            deleteOnExit()
            dir.deleteOnExit()
        }
    }

    /**
     * The file of the source of a [com.leko.forge.ide.semantics.ForgeResolution], as the analysis of [from] reports
     * it: `null` is [from] itself, [STD_SOURCE] the standard library, anything else the path of an imported file.
     */
    fun fileOfSource(source: String?, from: ForgeDocument): File? = when (source) {
        null -> from.file
        STD_SOURCE -> stdFile
        else -> File(source)
    }

    /** The URI of the source of a resolution (see [fileOfSource]). */
    fun uriOfSource(source: String?, from: ForgeDocument): String? =
        if (source == null) from.uri else fileOfSource(source, from)?.let(::uriOf)

    /** The text of the source of a resolution: of its editor if it is open. */
    fun textOfSource(source: String?, from: ForgeDocument): String? {
        if (source == null) return from.text
        val file = fileOfSource(source, from) ?: return null
        return documentOf(file)?.text ?: runCatching { file.readText() }.getOrNull()
    }

    companion object {
        private val LOG: Logger = Logger.getLogger("com.leko.forge.lsp")

        fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    }
}

/**
 * The analysis of a version of a document: its parse tree, its declarations, and, computed when they are first
 * needed, its semantics and its problems.
 */
class ForgeDocumentAnalysis(val document: ForgeDocument, private val workspace: ForgeWorkspace) {
    val text: String get() = document.text
    val positions = ForgeText(document.text)

    /** The tokens of ForgeParser, without whitespace and comments. */
    val tokens: List<Token> = ForgeAntlr.tokens(document.text)

    private val parsed = ForgeAntlr.parse(document.text)
    val tree: ForgeParser.RootContext get() = parsed.first

    val declarations = ForgeDeclarations(document.text, tree)

    /**
     * What the names resolve to. While the document can not be analyzed (e.g. while a declaration is typed), those of
     * its last version that could be, moved to where its lines are now.
     */
    val semantics: ForgeSemantics by lazy {
        val computed = ForgeSemantics.computeOrNull(text, document.file, workspace)
        if (computed != null) {
            workspace.lastGood[document.uri] = text to computed
            computed
        } else {
            workspace.lastGood[document.uri]?.let { (oldText, old) -> old.moved(ForgeLineShift.between(oldText, text)) } ?: ForgeSemantics.EMPTY
        }
    }

    /** The characters ForgeLexer has no token for. */
    private val badCharacters: List<ForgeTextRange> by lazy {
        val lexer = ForgeEditorLexer(text)
        buildList {
            while (true) {
                val type = lexer.tokenType ?: break
                if (type == ForgeEditorLexer.BAD_CHARACTER) add(ForgeTextRange(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
        }
    }

    /**
     * The problems of the document: the syntax errors of ForgeParser and the characters ForgeLexer has no token for,
     * or, if there are none, the errors and warnings of the compiler's analysis.
     */
    val problems: List<ForgeProblem> by lazy {
        val syntax = parsed.second.map { error ->
            val token = tokens.firstOrNull { it.type != Token.EOF && it.startIndex == error.offset }
            val range = if (token != null) ForgeTextRange(token.startIndex, token.stopIndex + 1)
            else ForgeTextRange(error.offset.coerceAtMost(text.length), (error.offset + 1).coerceAtMost(text.length))
            ForgeProblem(ForgeSeverity.ERROR, error.message, range)
        } + badCharacters.map { ForgeProblem(ForgeSeverity.ERROR, "unexpected character", it) }
        syntax.ifEmpty { ForgeDiagnostics.analyze(ForgeSource(text, document.file), workspace) }
    }

    /** The name (an IDENT) at [offset]: the one it is in, or the one it is right after (the caret after a name). */
    fun identifierAt(offset: Int): Token? =
        tokens.firstOrNull { it.type == ForgeLexer.IDENT && it.startIndex <= offset && offset < it.stopIndex + 1 }
            ?: tokens.firstOrNull { it.type == ForgeLexer.IDENT && it.stopIndex + 1 == offset }

    /** The token of ForgeParser at [offset], if any. */
    fun tokenAt(offset: Int): Token? =
        tokens.firstOrNull { it.type != Token.EOF && it.startIndex <= offset && offset <= it.stopIndex }
}
