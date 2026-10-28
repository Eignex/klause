package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.readText
import kotlin.time.TimeSource

// Platform seams for the CLI: the complete list of what differs between the JVM
// distribution and the native executables. Everything else is common code.

/** Read a CLI tuning knob by its dotted property name. JVM: the system property, then the env var
 *  spelled as the name uppercased with `.` mapped to `_` (e.g. `klause.engine` becomes
 *  `KLAUSE_ENGINE`). Native: that env var only (no system properties). */
internal expect fun cliProp(name: String): String?

internal expect fun platformDebugStats(): CliDebugStats

/** Load core [KlauseConfig] from the process environment via [cliProp] and install it as the
 *  ambient config consulted by the compiler/solver. Call once at startup, before loading a model. */
internal fun installCliConfig(): KlauseConfig =
    KlauseConfig.fromProps(lookup = ::cliProp).also { KlauseConfig.current = it }

/**
 * Heap diagnostics for `dry-run-presolve`: [retainedBytes] measures live heap after collection,
 * [committedBytes] measures heap reserved from the OS, and [peakBytes] is the sampler
 * maximum or null without sampling. Peaks include uncollected garbage and depend on
 * `-Xmx`; compare them only at equal heap ceilings.
 */
internal class HeapSample(val retainedBytes: Long, val committedBytes: Long, val peakBytes: Long?)

/** Begin recording the process's maximum heap usage, so [HeapSample.peakBytes] covers everything from
 *  here on. Call before the work being measured — it cannot see backwards. A no-op on a second call and
 *  on a platform with no heap accounting (native). Diagnostics only. */
internal expect fun startHeapPeakSampler()

/** Sample the heap, or null on a platform with no heap accounting (native). Collects first, so it is
 *  for diagnostics only and must never be called on a solve path. */
internal expect fun sampleHeap(): HeapSample?

internal expect fun flushCliOutput()

internal expect fun errPrintln(message: String)

internal expect fun exitCli(code: Int): Nothing

/** Open [path] as a streamed [CharSource]: an uncompressed file read incrementally, or a compressed one
 *  piped through the matching [DECOMPRESSORS] command (see [compressionExtension]). Front-ends consume
 *  the source instead of the whole-file [String], so a huge instance is never fully resident. */
internal expect fun openFileSource(path: String): CharSource

internal inline fun <T> CharSource.useSource(read: (CharSource) -> T): T =
    (this as? AutoCloseable).use { read(this) }

/** Read [path] fully into a [String] through [openFileSource], for a consumer that needs the whole text. */
internal fun readTextFile(path: String): String = openFileSource(path).useSource { it.readText() }

private val timeOrigin = TimeSource.Monotonic.markNow()

/** Monotonic clock for deadlines — only ever compared against itself. */
internal fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

internal fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

/** Compression suffix → the external decompress-to-stdout command. Shelling out to the system tools
 *  keeps the CLI free of a compression dependency; [openFileSource] pipes the file through the matching
 *  command. */
internal val DECOMPRESSORS = mapOf(
    "xz" to listOf("xz", "-dc"),
    "gz" to listOf("gzip", "-dc"),
    "bz2" to listOf("bzip2", "-dc"),
    "zst" to listOf("zstd", "-dcq"),
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
