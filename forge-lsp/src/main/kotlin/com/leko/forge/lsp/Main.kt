package com.leko.forge.lsp

import org.eclipse.lsp4j.launch.LSPLauncher
import java.io.PrintStream

/** Runs the Forge language server on stdin and stdout. */
fun main() {
    // stdout carries the protocol: whatever else prints goes to stderr
    val protocolOut = PrintStream(System.out, true)
    System.setOut(System.err)
    val server = ForgeLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, protocolOut)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}
