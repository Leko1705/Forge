package com.leko.forge.lsp

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.launch.LSPLauncher
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server over JSON-RPC, as an editor talks to it. */
class ForgeProtocolTest {

    @Test
    fun anEditorSessionOverJsonRpc() {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 1 shl 16)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 1 shl 16)

        var exitCode: Int? = null
        val server = ForgeLanguageServer(diagnosticsDelay = 0, onExit = { exitCode = it })
        val serverLauncher = LSPLauncher.createServerLauncher(server, serverIn, toClient)
        server.connect(serverLauncher.remoteProxy)
        serverLauncher.startListening()

        val client = RecordingClient()
        val clientLauncher = LSPLauncher.createClientLauncher(client, clientIn, toServer)
        clientLauncher.startListening()
        val remote = clientLauncher.remoteProxy

        val result = remote.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).get(30, TimeUnit.SECONDS)
        assertEquals("forge-lsp", result.serverInfo.name)
        assertEquals(ForgeFeatures.TYPES, result.capabilities.semanticTokensProvider.legend.tokenTypes)
        remote.initialized(InitializedParams())

        val content = "node A(n: int)\nsyn A.x: int = \"text\""
        val uri = "untitled:Untitled-1"
        remote.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "forge", 1, content)))
        val deadline = System.currentTimeMillis() + 30_000
        while (client.diagnosticsOf(uri) == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val diagnostics = client.diagnosticsOf(uri)
        assertEquals(listOf("expected type 'int' but got str"), diagnostics?.map { it.message })

        val hover = remote.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 5))).get(30, TimeUnit.SECONDS)
        assertTrue("node A(n: int)" in hover.contents.right.value, hover.contents.right.value)

        remote.shutdown().get(30, TimeUnit.SECONDS)
        remote.exit()
        val exitDeadline = System.currentTimeMillis() + 10_000
        while (exitCode == null && System.currentTimeMillis() < exitDeadline) Thread.sleep(20)
        assertEquals(0, exitCode)
    }
}
