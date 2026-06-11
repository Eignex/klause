package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.config.installKlauseConfigFromEnv
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioWorker
import java.io.File
import kotlin.system.exitProcess

internal actual fun cliProp(name: String): String? =
    System.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_'))

internal actual fun installCliConfig(): KlauseConfig = installKlauseConfigFromEnv()

internal actual fun errPrintln(message: String) = System.err.println(message)

internal actual fun exitCli(code: Int): Nothing = exitProcess(code)

internal actual fun readTextFile(path: String): String = File(path).readText()

internal actual fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor = Portfolio(workers)
