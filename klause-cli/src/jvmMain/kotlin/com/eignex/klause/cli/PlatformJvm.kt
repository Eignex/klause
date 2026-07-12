package com.eignex.klause.cli

import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioWorker
import java.io.File
import kotlin.system.exitProcess

internal actual fun cliProp(name: String): String? =
    System.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_'))

internal actual fun errPrintln(message: String) = System.err.println(message)

internal actual fun exitCli(code: Int): Nothing = exitProcess(code)

internal actual fun readTextFile(path: String): String {
    val ext = compressionExtension(path) ?: return File(path).readText()
    val cmd = DECOMPRESSORS.getValue(ext) + path
    val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val text = proc.inputStream.bufferedReader().use { it.readText() }
    val code = proc.waitFor()
    require(code == 0) { "decompressing '$path' via '${cmd[0]}' failed (exit $code); is '${cmd[0]}' installed?" }
    return text
}

internal actual fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor = Portfolio(workers)
