package com.leko.forge.ide

import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CharStream
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * What the analysis of a file needs from the editor it runs in: the text of the files the file imports, which an
 * editor may have changed without saving them, how the editor cancels an analysis, and where problems are logged.
 */
fun interface ForgeHost {

    /**
     * The text of the imported [file], with its absolute path as the name of the stream. As UTF-16 chars
     * ([ANTLRInputStream]), like the editors, so that the columns of the compiler's spans are offsets in lines.
     */
    fun read(file: File): CharStream

    /** Whether [e], thrown while an analysis runs (e.g. by [read]), cancels it: it is thrown on, not reported. */
    fun cancels(e: Throwable): Boolean = false

    /** Logs [message] for the developers of the editor support, e.g. that the analysis failed. */
    fun log(level: Level, message: String, e: Throwable? = null) {
        LOG.log(level, message, e)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger("com.leko.forge.ide")

        /** The text of a file on disk, as the compiler reads it with `-f`. */
        fun readFromDisk(file: File): CharStream = ANTLRInputStream(file.readText()).apply { name = file.absolutePath }

        /** A host that reads the imported files from disk. */
        val DISK: ForgeHost = ForgeHost(::readFromDisk)
    }
}
