package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioWorker
import kotlin.system.exitProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fprintf
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.rewind
import platform.posix.stderr

@OptIn(ExperimentalForeignApi::class)
internal actual fun cliProp(name: String): String? = getenv(name.uppercase().replace('.', '_'))?.toKString()

/** Native counterpart of `klauseConfigFromEnv`: env vars only (no system properties). */
internal actual fun installCliConfig(): KlauseConfig {
    val falsey = setOf("0", "false", "off", "no")
    val base = KlauseConfig.current
    val config = base.copy(
        pinAbsentOptVars = cliProp("klause.pinAbsentOpt")?.let { it.trim().lowercase() !in falsey }
            ?: base.pinAbsentOptVars,
        unboundedIntLo = cliProp("klause.fzn.unboundedIntLo")?.trim()?.toIntOrNull()
            ?: base.unboundedIntLo,
        unboundedIntHi = cliProp("klause.fzn.unboundedIntHi")?.trim()?.toIntOrNull()
            ?: base.unboundedIntHi,
        floatBuckets = cliProp("klause.floatBuckets")?.trim()?.toIntOrNull()
            ?: base.floatBuckets,
        floatScale = cliProp("klause.floatScale")?.trim()?.toLongOrNull()
            ?: base.floatScale,
    )
    KlauseConfig.current = config
    return config
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun errPrintln(message: String) {
    fprintf(stderr, "%s\n", message)
}

internal actual fun exitCli(code: Int): Nothing = exitProcess(code)

@OptIn(ExperimentalForeignApi::class)
internal actual fun readTextFile(path: String): String {
    val f = fopen(path, "rb") ?: error("cannot open $path")
    try {
        fseek(f, 0L, SEEK_END)
        val size = ftell(f)
        rewind(f)
        if (size <= 0L) return ""
        val bytes = ByteArray(size.toInt())
        val read = bytes.usePinned { fread(it.addressOf(0), 1u, size.toULong(), f) }
        return bytes.copyOf(read.toInt()).decodeToString()
    } finally {
        fclose(f)
    }
}

internal actual fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor = Portfolio(workers)
