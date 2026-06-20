package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioWorker
import kotlin.time.TimeSource

// Platform seams for the CLI: the complete list of what differs between the JVM
// distribution and the native executables. Everything else is common code.

/** Read a CLI tuning knob by its dotted property name. JVM: the system property, then the env var
 *  spelled as the name uppercased with `.` mapped to `_` (e.g. `klause.engine` becomes
 *  `KLAUSE_ENGINE`). Native: that env var only (no system properties). */
internal expect fun cliProp(name: String): String?

/** Load core [KlauseConfig] from the process environment via [cliProp] and install it as the
 *  ambient config consulted by the compiler/solver. Call once at startup, before loading a model. */
internal fun installCliConfig(): KlauseConfig =
    KlauseConfig.fromProps(lookup = ::cliProp).also { KlauseConfig.current = it }

internal expect fun errPrintln(message: String)

internal expect fun exitCli(code: Int): Nothing

internal expect fun readTextFile(path: String): String

/** Build the parallel [PortfolioExecutor] (the multi-threaded `Portfolio`), which lives in klause's
 *  jvm+native source set — `commonMain` has no threads (js/wasm are single-threaded). The CLI targets
 *  only jvm+native, so this seam is satisfiable on every CLI target. */
internal expect fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor

private val timeOrigin = TimeSource.Monotonic.markNow()

/** Monotonic clock for deadlines — only ever compared against itself. */
internal fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

internal fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

internal fun fileExtension(path: String): String = fileName(path).substringAfterLast('.', "")
