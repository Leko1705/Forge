package com.leko.forge.lsp

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * The Forge language server. The compiler keeps the attributes of its trees in one global table, so everything
 * that analyzes runs on one thread, in the order it was asked for; diagnostics are published a moment after the
 * last change ([diagnosticsDelay] milliseconds), for every open document, as a change of one file may change the
 * analysis of the files that import it.
 */
class ForgeLanguageServer(
    private val diagnosticsDelay: Long = 250,
    /** What `exit` does; the process exits, except in tests. */
    private val onExit: (Int) -> Unit = { System.exit(it) },
) : LanguageServer, LanguageClientAware {

    val workspace = ForgeWorkspace()
    private val features = ForgeFeatures(workspace)
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "forge-analysis").apply { isDaemon = true } }
    @Volatile private var client: LanguageClient? = null
    @Volatile private var shutdown = false
    private var pendingDiagnostics: ScheduledFuture<*>? = null

    override fun connect(client: LanguageClient) {
        this.client = client
        workspace.onLog = { level, message ->
            client.logMessage(MessageParams(if (level.intValue() >= Level.SEVERE.intValue()) MessageType.Error else MessageType.Warning, message))
        }
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncOptions().apply {
                openClose = true
                change = TextDocumentSyncKind.Incremental
                setSave(SaveOptions(false))
            })
            completionProvider = CompletionOptions(false, listOf(".", "@"))
            setHoverProvider(true)
            setDefinitionProvider(true)
            setReferencesProvider(true)
            setDocumentSymbolProvider(true)
            setFoldingRangeProvider(true)
            semanticTokensProvider = SemanticTokensWithRegistrationOptions(ForgeFeatures.LEGEND, true, false)
        }
        return CompletableFuture.completedFuture(InitializeResult(capabilities, ServerInfo("forge-lsp", "1.0")))
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdown = true
        return CompletableFuture.supplyAsync({ null }, executor)
    }

    override fun exit() {
        executor.shutdownNow()
        onExit(if (shutdown) 0 else 1)
    }

    /** Runs [block] on the analysis thread; a failure is logged and answered with [fallback]. */
    private fun <T> analyze(name: String, fallback: T, block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({
        try {
            block()
        } catch (e: Exception) {
            workspace.log(Level.WARNING, "the Forge language server failed on $name", e)
            fallback
        }
    }, executor)

    private fun <T> at(params: TextDocumentPositionParams, name: String, fallback: T, block: (ForgeDocumentAnalysis, Int) -> T): CompletableFuture<T> =
        analyze(name, fallback) {
            val analysis = workspace.analysis(params.textDocument.uri) ?: return@analyze fallback
            block(analysis, analysis.positions.offset(params.position))
        }

    // ---- diagnostics -----------------------------------------------------------------------------------------

    /** Publishes the diagnostics of the open documents a moment from now, [first] first. */
    private fun scheduleDiagnostics(first: String?) {
        synchronized(this) {
            pendingDiagnostics?.cancel(false)
            pendingDiagnostics = executor.schedule({ publishDiagnostics(first) }, diagnosticsDelay, TimeUnit.MILLISECONDS)
        }
    }

    private fun publishDiagnostics(first: String?) {
        val client = client ?: return
        val uris = workspace.openDocuments.map { it.uri }.sortedBy { it != first }
        for (uri in uris) {
            try {
                val analysis = workspace.analysis(uri) ?: continue
                client.publishDiagnostics(PublishDiagnosticsParams(uri, features.diagnostics(analysis), analysis.document.version))
            } catch (e: Exception) {
                workspace.log(Level.WARNING, "the diagnostics of $uri failed", e)
            }
        }
    }

    /** Waits until everything asked for so far is done; for tests. */
    fun awaitIdle() {
        val pending = synchronized(this) { pendingDiagnostics }
        runCatching { pending?.get() }
        CompletableFuture.supplyAsync({}, executor).get()
    }

    // ---- documents -------------------------------------------------------------------------------------------

    private val textDocuments = object : TextDocumentService {
        override fun didOpen(params: DidOpenTextDocumentParams) {
            val document = params.textDocument
            workspace.open(document.uri, document.version, document.text)
            scheduleDiagnostics(document.uri)
        }

        override fun didChange(params: DidChangeTextDocumentParams) {
            val uri = params.textDocument.uri
            var text = workspace.document(uri)?.text ?: return
            for (change in params.contentChanges) text = ForgeText(text).apply(change)
            workspace.change(uri, params.textDocument.version, text)
            scheduleDiagnostics(uri)
        }

        override fun didClose(params: DidCloseTextDocumentParams) {
            val uri = params.textDocument.uri
            workspace.close(uri)
            client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
            scheduleDiagnostics(null)
        }

        override fun didSave(params: DidSaveTextDocumentParams) {
            workspace.filesChanged()
            scheduleDiagnostics(params.textDocument.uri)
        }

        override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> =
            at(params, "completion", Either.forLeft(emptyList())) { analysis, offset -> Either.forLeft(features.completion(analysis, offset)) }

        override fun hover(params: HoverParams): CompletableFuture<Hover?> =
            at(params, "hover", null) { analysis, offset -> features.hover(analysis, offset) }

        override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
            at(params, "definition", Either.forLeft(emptyList())) { analysis, offset -> Either.forLeft(features.definition(analysis, offset)) }

        override fun references(params: ReferenceParams): CompletableFuture<List<Location>> =
            at(params, "references", emptyList()) { analysis, offset ->
                features.references(analysis, offset, params.context?.isIncludeDeclaration ?: true)
            }

        override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> =
            analyze("document symbols", emptyList()) {
                val analysis = workspace.analysis(params.textDocument.uri) ?: return@analyze emptyList()
                features.documentSymbols(analysis).map { Either.forRight(it) }
            }

        override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> =
            analyze("semantic tokens", SemanticTokens(emptyList())) {
                val analysis = workspace.analysis(params.textDocument.uri) ?: return@analyze SemanticTokens(emptyList())
                features.semanticTokens(analysis)
            }

        override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> =
            analyze("folding ranges", emptyList()) {
                val analysis = workspace.analysis(params.textDocument.uri) ?: return@analyze emptyList()
                features.foldingRanges(analysis)
            }
    }

    private val workspaceService = object : WorkspaceService {
        override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}

        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
            workspace.filesChanged()
            scheduleDiagnostics(null)
        }
    }

    override fun getTextDocumentService(): TextDocumentService = textDocuments
    override fun getWorkspaceService(): WorkspaceService = workspaceService
}
