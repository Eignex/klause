package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.readText
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

/**
 * A heap reading for the `dry-run-presolve` diagnostics. [retainedBytes] is live heap after a
 * collection — what the phase still holds — and [committedBytes] the heap the JVM has taken from the OS,
 * which it grows under pressure and rarely returns, so at the end of ingest it approximates the
 * high-water demand. The two diverge when a phase allocates a large transient (issue #1415).
 */
internal class HeapSample(val retainedBytes: Long, val committedBytes: Long)

/** Sample the heap, or null on a platform with no heap accounting (native). Collects first, so it is
 *  for diagnostics only and must never be called on a solve path. */
internal expect fun sampleHeap(): HeapSample?

internal expect fun errPrintln(message: String)

internal expect fun exitCli(code: Int): Nothing

/** Open [path] as a streamed [CharSource]: an uncompressed file read incrementally, or a compressed one
 *  piped through the matching [DECOMPRESSORS] command (see [compressionExtension]). Front-ends consume
 *  the source instead of the whole-file [String], so a huge instance is never fully resident. */
internal expect fun openFileSource(path: String): CharSource

/** Read [path] fully into a [String] — the bridge for a front-end that still parses a materialized
 *  [String]. A thin adapter over [openFileSource]; a converted front-end takes the source directly. */
internal fun readTextFile(path: String): String = openFileSource(path).readText()

/** Build the parallel [PortfolioExecutor] (the multi-threaded `Portfolio`), which lives in klause's
 *  jvm+native source set — `commonMain` has no threads (js/wasm are single-threaded). The CLI targets
 *  only jvm+native, so this seam is satisfiable on every CLI target. */
internal expect fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor

private val timeOrigin = TimeSource.Monotonic.markNow()

/** Monotonic clock for deadlines — only ever compared against itself. */
internal fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

internal fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

/** Compression suffix → the external decompress-to-stdout command. Shelling out to the system tools
 *  (the mature reference implementations, ubiquitous on any bench/competition box) keeps the CLI free
 *  of a compression dependency; [readTextFile] pipes the file through the matching command. */
internal val DECOMPRESSORS = mapOf(
    "xz" to listOf("xz", "-dc"),
    "gz" to listOf("gzip", "-dc"),
    "bz2" to listOf("bzip2", "-dc"),
)

/** The trailing compression suffix (lowercased) when [path] names a compressed file, else null. */
internal fun compressionExtension(path: String): String? =
    fileName(path).substringAfterLast('.', "").lowercase().takeIf { it in DECOMPRESSORS }

/** The format-determining extension: the suffix after any compression suffix (`foo.cnf.xz` → `cnf`),
 *  so a compressed instance routes to the same front-end as its uncompressed form. */
internal fun fileExtension(path: String): String {
    val name = fileName(path)
    val last = name.substringAfterLast('.', "")
    return if (last.lowercase() in DECOMPRESSORS) name.substringBeforeLast('.').substringAfterLast('.', "") else last
}
