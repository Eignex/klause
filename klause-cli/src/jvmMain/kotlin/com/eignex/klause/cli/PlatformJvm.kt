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

internal actual fun isDirectory(path: String): Boolean = File(path).isDirectory

internal actual fun walkFiles(root: String): List<String> {
    val r = File(root)
    return if (r.isDirectory) {
        r.walkTopDown().filter { it.isFile }.map { it.path }.toList()
    } else {
        listOf(root)
    }
}

internal actual fun <T> runBlockingBridge(block: suspend () -> T): T = runBlocking { block() }
