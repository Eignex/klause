package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.config.installKlauseConfigFromEnv
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

internal actual fun cliProp(name: String): String? =
    System.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_'))

internal actual fun installCliConfig(): KlauseConfig = installKlauseConfigFromEnv()

internal actual fun errPrintln(message: String) = System.err.println(message)

internal actual fun exitCli(code: Int): Nothing = exitProcess(code)

internal actual fun readTextFile(path: String): String = File(path).readText()

internal actual fun <T> runBlockingBridge(block: suspend () -> T): T = runBlocking { block() }
