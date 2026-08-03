package com.eignex.klause.cli

import com.eignex.klause.io.CharSource
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioWorker
import java.io.File
import java.io.Reader
import kotlin.system.exitProcess

internal actual fun cliProp(name: String): String? =
    System.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_'))

internal actual fun errPrintln(message: String) = System.err.println(message)

internal actual fun exitCli(code: Int): Nothing = exitProcess(code)

internal actual fun openFileSource(path: String): CharSource {
    val ext = compressionExtension(path) ?: return ReaderCharSource(File(path).bufferedReader())
    val cmd = DECOMPRESSORS.getValue(ext) + path
    val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    return ReaderCharSource(proc.inputStream.bufferedReader()) {
        val code = proc.waitFor()
        require(code == 0) { "decompressing '$path' via '${cmd[0]}' failed (exit $code); is '${cmd[0]}' installed?" }
    }
}

/** Streams a [Reader] in fixed char chunks; runs [onEof] once the reader is drained (the decompressor
 *  exit-code check). The [Reader] already decodes bytes → chars, so a chunk never splits a code point. */
private class ReaderCharSource(private val reader: Reader, private val onEof: () -> Unit = {}) : CharSource {
    private val buffer = CharArray(size = 64 * 1024)
    private var done = false

    override fun next(): String? {
        if (done) return null
        val n = reader.read(buffer)
        if (n < 0) {
            done = true
            reader.close()
            onEof()
            return null
        }
        return String(buffer, 0, n)
    }
}

internal actual fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor = Portfolio(workers)
