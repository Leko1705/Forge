package com.leko.forge.lsp

import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A client that records what the server sends. */
class RecordingClient : LanguageClient {
    val diagnostics = CopyOnWriteArrayList<PublishDiagnosticsParams>()
    val log = CopyOnWriteArrayList<String>()

    /** The last diagnostics published for [uri]. */
    fun diagnosticsOf(uri: String) = diagnostics.lastOrNull { it.uri == uri }?.diagnostics

    override fun publishDiagnostics(params: PublishDiagnosticsParams) { diagnostics += params }
    override fun logMessage(message: MessageParams) { log += message.message }
    override fun showMessage(message: MessageParams) { log += message.message }
    override fun telemetryEvent(`object`: Any?) {}
    override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
}

class ForgeLanguageServerTest {
    private val client = RecordingClient()
    private val server = ForgeLanguageServer(diagnosticsDelay = 0, onExit = {}).apply {
        connect(client)
        initialize(InitializeParams()).get()
    }
    private val dir = kotlin.io.path.createTempDirectory("forge-lsp").toFile()

    @AfterTest
    fun cleanUp() {
        server.shutdown().get()
        dir.deleteRecursively()
    }

    private val text get() = server.textDocumentService

    /** Opens [content] as the file [name] (written to disk too, so that it can be imported); its URI. */
    private fun open(name: String, content: String): String {
        val file = File(dir, name).apply { writeText(content) }
        val uri = uriOf(file)
        text.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "forge", 1, content)))
        server.awaitIdle()
        return uri
    }

    /** The position after [marker] in [content] (the start of the name that follows it). */
    private fun after(content: String, marker: String, delta: Int = 0): Position {
        val index = content.indexOf(marker)
        assertTrue(index >= 0, "no '$marker'")
        return ForgeText(content).position(index + marker.length + delta)
    }

    private fun lineOf(content: String, part: String) = content.lineSequence().indexOfFirst { part in it }

    @Test
    fun publishesTheErrorsOfTheAnalysisWithTheirCodes() {
        val content = "node A(n: int)\nsyn A.x: int = \"text\"\nsyn A.y: Missing = 0"
        val uri = open("a.forge", content)
        val errors = assertNotNull(client.diagnosticsOf(uri)).filter { it.severity == DiagnosticSeverity.Error }
        val forge = ForgeText(content)
        assertEquals(setOf("\"text\"", "Missing"), errors.map { content.substring(forge.offset(it.range.start), forge.offset(it.range.end)) }.toSet())
        val typeError = errors.first { content.substring(forge.offset(it.range.start)).startsWith("\"text\"") }
        assertEquals("E009", typeError.code.left)
        assertEquals("expected type 'int' but got str", typeError.message)
    }

    @Test
    fun publishesSyntaxErrorsAndUnexpectedCharacters() {
        val uri = open("a.forge", "node A(n: int\nnode B §")
        val messages = assertNotNull(client.diagnosticsOf(uri)).map { it.message }
        assertTrue(messages.any { "unexpected character" in it }, "$messages")
        assertTrue(messages.size >= 2, "$messages")
    }

    @Test
    fun incrementalChangesAreAppliedAndRecheckAllOpenFiles() {
        val lib = open("lib.forge", "node Lib(n: int)")
        val main = open("main.forge", "import \"lib.forge\"\nsyn Lib.x: int = this.n")
        assertEquals(emptyList(), client.diagnosticsOf(main))
        // rename the field in the open (unsaved) library: the importing file sees it
        text.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(lib, 2), listOf(
            TextDocumentContentChangeEvent(Range(Position(0, 9), Position(0, 10)), "m"),
        )))
        server.awaitIdle()
        assertEquals("node Lib(m: int)", server.workspace.document(lib)!!.text)
        val errors = assertNotNull(client.diagnosticsOf(main))
        assertTrue(errors.any { it.severity == DiagnosticSeverity.Error }, "$errors")
        text.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(main)))
        server.awaitIdle()
        assertEquals(emptyList(), client.diagnosticsOf(main))
    }

    @Test
    fun goesToDefinitions() {
        val uri = open("a.forge", PROGRAM)
        fun definition(marker: String, delta: Int = 0): Location =
            text.definition(DefinitionParams(TextDocumentIdentifier(uri), after(PROGRAM, marker, delta))).get().left.single()
        assertEquals(lineOf(PROGRAM, "node Num(value"), definition("if this.").range.start.line)
        assertEquals(lineOf(PROGRAM, "syn Expr.type"), definition("match this.left.").range.start.line)
        assertEquals(lineOf(PROGRAM, "val Type.isError"), definition("this.left.type.").range.start.line)
        assertEquals(lineOf(PROGRAM, "inh depth"), definition("twice(this.").range.start.line)
        assertEquals(lineOf(PROGRAM, "const limit"), definition("> ").range.start.line)
        // in the middle of the name, and right after it
        assertEquals(lineOf(PROGRAM, "fun twice"), definition("|| ", 2).range.start.line)
        assertEquals(lineOf(PROGRAM, "fun twice"), definition("|| twice").range.start.line)
        // the standard library: written to a file
        val upper = definition("(m.")
        assertTrue(upper.uri.endsWith("std.forge"), upper.uri)
        assertTrue("upper" in File(java.net.URI(upper.uri)).readLines()[upper.range.start.line])
    }

    @Test
    fun goesToImportedFiles() {
        open("lib.forge", "node Lib(n: int)")
        val content = "import \"lib.forge\"\nsyn Lib.x: int = this.n"
        val uri = open("main.forge", content)
        val toFile = text.definition(DefinitionParams(TextDocumentIdentifier(uri), after(content, "import \"", 1))).get().left.single()
        assertEquals(uriOf(File(dir, "lib.forge")), toFile.uri)
        val toField = text.definition(DefinitionParams(TextDocumentIdentifier(uri), after(content, "this."))).get().left.single()
        assertEquals(uriOf(File(dir, "lib.forge")), toField.uri)
        assertEquals(Range(Position(0, 9), Position(0, 10)), toField.range)
    }

    @Test
    fun findsReferences() {
        val uri = open("a.forge", PROGRAM)
        val position = after(PROGRAM, "syn Expr.")
        val references = text.references(ReferenceParams(TextDocumentIdentifier(uri), position, ReferenceContext(true))).get()
        val forge = ForgeText(PROGRAM)
        assertTrue(references.all { PROGRAM.substring(forge.offset(it.range.start), forge.offset(it.range.end)) == "type" }, "$references")
        // the declaration, `this.left.type` and `this.right.type` in Add.type, `this.left.type` in Add.bad
        assertEquals(4, references.size, "$references")
    }

    @Test
    fun showsTheDocumentationOnHover() {
        val content = "/** A number, read from the source. */\nnode Num(value: int)\nsyn Num.twice: int = this.value * 2"
        val uri = open("a.forge", content)
        val onNode = text.hover(HoverParams(TextDocumentIdentifier(uri), after(content, "node "))).get().contents.right.value
        assertTrue("node Num(value: int)" in onNode && "A number, read from the source." in onNode && "*node*" in onNode, onNode)
        val onUse = text.hover(HoverParams(TextDocumentIdentifier(uri), after(content, "this."))).get().contents.right.value
        assertTrue("value: int" in onUse && "child of node Num" in onUse, onUse)
    }

    @Test
    fun completesMembers() {
        val content = "node A(count: int)\nsyn A.x: int = this."
        val uri = open("a.forge", content)
        val items = text.completion(CompletionParams(TextDocumentIdentifier(uri), after(content, "this."))).get().left
        val count = assertNotNull(items.firstOrNull { it.label == "count" }, "${items.map { it.label }}")
        assertEquals(CompletionItemKind.Field, count.kind)
        val keywordUri = open("k.forge", "\nnode B(n: int)")
        val keywords = text.completion(CompletionParams(TextDocumentIdentifier(keywordUri), Position(0, 0))).get().left
        assertTrue(keywords.any { it.label == "node" && it.kind == CompletionItemKind.Keyword }, "${keywords.map { it.label }}")
        // a call: parentheses follow
        val calls = "fun twice(x: int): int = x * 2\nconst c = tw"
        val callUri = open("b.forge", calls)
        val twice = text.completion(CompletionParams(TextDocumentIdentifier(callUri), after(calls, "= tw"))).get().left.first { it.label == "twice" }
        assertEquals(InsertTextFormat.Snippet, twice.insertTextFormat)
        assertEquals("twice($1)$0", twice.insertText)
        // nothing in comments
        val comment = "// this."
        val commentUri = open("c.forge", comment)
        assertEquals(emptyList(), text.completion(CompletionParams(TextDocumentIdentifier(commentUri), after(comment, "this."))).get().left)
    }

    @Test
    fun listsTheDeclarations() {
        val uri = open("a.forge", PROGRAM)
        val symbols = text.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).get().map { it.right }
        val byName = symbols.associateBy { it.name }
        assertEquals(SymbolKind.Interface, byName.getValue("Expr").kind)
        assertEquals(SymbolKind.Class, byName.getValue("Add").kind)
        assertEquals(listOf("left: Expr", "right: Expr"), byName.getValue("Add").children.map { it.detail })
        assertEquals(SymbolKind.Enum, byName.getValue("Type").kind)
        assertEquals(listOf("INT", "ERROR"), byName.getValue("Type").children.map { it.name })
        assertEquals(SymbolKind.Struct, byName.getValue("Diagnostic").kind)
        assertEquals("syn Num.type: Type", byName.getValue("Num.type").detail)
        assertEquals(SymbolKind.Function, byName.getValue("twice").kind)
        assertEquals(SymbolKind.Constant, byName.getValue("limit").kind)
    }

    @Test
    fun colorsNamesByWhatTheyAre() {
        val content = "node A(n: int)\nsyn A.size: int = 1\nsyn A.x: int = this.n + this.size"
        val uri = open("a.forge", content)
        val data = text.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).get().data
        // decode: (line, column) -> type
        val tokens = mutableMapOf<Pair<Int, Int>, String>()
        var line = 0
        var column = 0
        for (i in data.indices step 5) {
            line += data[i]
            column = if (data[i] == 0) column + data[i + 1] else data[i + 1]
            tokens[line to column] = ForgeFeatures.TYPES[data[i + 3]]
        }
        val last = content.lines()[2]
        assertEquals("class", tokens[0 to 5])
        assertEquals("property", tokens[2 to last.indexOf("n +")], "a field: $tokens")
        assertEquals("attribute", tokens[2 to last.lastIndexOf("size")], "a syn attribute: $tokens")
    }

    @Test
    fun foldsDeclarationsOverSeveralLines() {
        val content = "/**\n * doc\n */\nrecord R {\n  a: int,\n  b: int\n}\nnode A(n: int)"
        val uri = open("a.forge", content)
        val ranges = text.foldingRange(FoldingRangeRequestParams(TextDocumentIdentifier(uri))).get()
        assertEquals(setOf(0 to 2, 3 to 6), ranges.map { it.startLine to it.endLine }.toSet())
    }

    @Test
    fun theExamplesHaveNoErrors() {
        val root = File(System.getProperty("forge.root") ?: ".")
        val file = File(root, "examples/kiln/kiln.forge")
        val uri = uriOf(file)
        text.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "forge", 1, file.readText())))
        server.awaitIdle()
        assertEquals(emptyList(), assertNotNull(client.diagnosticsOf(uri)).filter { it.severity == DiagnosticSeverity.Error })
    }

    companion object {
        val PROGRAM = """
            node Program(decls: List<Expr>)
            node Expr
            node Num(value: int): Expr
            node Add(left: Expr, right: Expr): Expr
            enum Type { INT, ERROR(msg: str) }
            record Diagnostic { msg: str, line: int }
            const limit = 3
            syn Expr.type: Type
            syn Num.type: Type = if this.value > limit then Type.INT else Type.ERROR("small")
            syn Add.type: Type = match this.left.type { Type.INT -> this.right.type  Type.ERROR(m) -> Type.ERROR(m.upper()) }
            val Type.isError: bool = match this { Type.ERROR(_) -> true  _ -> false }
            syn Add.bad: bool = this.left.type.isError || twice(this.depth) > 2
            fun twice(x: int): int = x * 2
            inh depth: int = 0
            inh Add.depth { left <- this.depth + 1  right <- this.depth + 1 }
        """.trimIndent()
    }
}
