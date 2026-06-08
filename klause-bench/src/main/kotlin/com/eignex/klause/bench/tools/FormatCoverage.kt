package com.eignex.klause.bench.tools

import com.eignex.klause.bench.catalog.ExternalCollection
import com.eignex.klause.bench.catalog.ExternalCollections
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.formats.smtlib.SmtLibQfLia
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.formats.xcsp3.UnsupportedXcsp3Exception
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coverage report against the XCSP3 competition library (and the SMT-LIB QF_LIA set): how
 * many instances **parse** into a klause [Problem], how many **solve** within a budget, and
 * which **unsupported constructs** account for the rest.
 *
 * The collections are fetched on demand by [CorpusFetcher]; XCSP3 instances are individually
 * `*.xml.lzma`-compressed and decompressed on the fly (no multi-GB expansion on disk).
 *
 * Knobs (system properties):
 *  - `klause.coverage.solve`        solve as well as parse (default `true`)
 *  - `klause.coverage.timeMs`       per-instance solve budget (default `2000`)
 *  - `klause.coverage.maxBytes`     skip instances whose decompressed XML exceeds this
 *                                   (default `20_000_000`; very large instances are slow/OOM-prone)
 *  - `klause.coverage.limit`        cap on instances processed (`0` = all)
 *  - `klause.coverage.progressEvery` progress line cadence (default `500`)
 */
object FormatCoverage {
    private val solve get() = System.getProperty("klause.coverage.solve")?.toBooleanStrictOrNull() ?: true
    private val timeMs get() = System.getProperty("klause.coverage.timeMs")?.toLongOrNull() ?: 2000L
    private val maxBytes get() = System.getProperty("klause.coverage.maxBytes")?.toLongOrNull() ?: 20_000_000L
    private val limit get() = System.getProperty("klause.coverage.limit")?.toIntOrNull() ?: 0
    private val progressEvery get() = System.getProperty("klause.coverage.progressEvery")?.toIntOrNull() ?: 500
    private val negTableCap get() = System.getProperty("klause.coverage.negTableCap")?.toLongOrNull() ?: 1_000_000L

    /** Per-instance wall-clock cap covering **parse + solve** together. A pathological
     *  instance that exceeds it is recorded as `timeout` rather than stalling the whole run.
     *  Must be ≥ the solve budget [timeMs]. */
    private val instanceMs get() = System.getProperty("klause.coverage.instanceMs")?.toLongOrNull() ?: 30_000L

    internal fun xcsp3() {
        val roots = ExternalCollections.xcsp3Competition.map { ensure(it) }
        val files = roots.flatMap { walk(it, setOf("lzma", "xz", "xml")) }.sortedBy { it.path }
        report("XCSP3 competition", files) { text ->
            val p = Xcsp3.parse(text, negTableCap)
            Parsed(p.problem, p.objective)
        }
    }

    internal fun smtlib() {
        val root = ensure(ExternalCollections.smtlibQfLia)
        val files = walk(root, setOf("smt2")).sortedBy { it.path }
        report("SMT-LIB QF_LIA", files) { text ->
            val p = SmtLibQfLia.parse(text)
            Parsed(p.problem, p.objective)
        }
    }

    private fun ensure(c: ExternalCollection): File = CorpusFetcher.ensure(c)

    private fun walk(root: File, exts: Set<String>): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension.lowercase() in exts }.toList()

    private class Parsed(val problem: Problem, val objective: LinearObjective?)

    private fun report(label: String, allFiles: List<File>, parse: (String) -> Parsed) {
        val files = if (limit > 0) allFiles.take(limit) else allFiles
        var parsed = 0
        var solved = 0
        var unknown = 0
        var unsupported = 0
        var parseError = 0
        var skipped = 0
        var timeout = 0
        val reasons = HashMap<String, Int>()
        println(
            "[coverage] $label: ${files.size} instances " +
                "(solve=$solve, solveBudget=${timeMs}ms, instanceTimeout=${instanceMs}ms)",
        )

        files.forEachIndexed { i, f ->
            if ((i + 1) % progressEvery == 0) {
                println(
                    "[coverage]   ${i + 1}/${files.size}  parsed=$parsed solved=$solved " +
                        "unsupported=$unsupported skipped=$skipped timeout=$timeout",
                )
            }
            when (val r = runBounded(instanceMs) { processOne(f, parse) }) {
                null -> timeout++

                R.Skip -> skipped++

                R.Parsed -> parsed++

                R.Solved -> {
                    parsed++
                    solved++
                }

                R.Unknown -> {
                    parsed++
                    unknown++
                }

                is R.Unsupported -> {
                    unsupported++
                    reasons.merge(r.reason, 1, Int::plus)
                }

                is R.ParseError -> {
                    parseError++
                    reasons.merge(r.reason, 1, Int::plus)
                }
            }
        }

        println()
        println("=== $label coverage ===")
        println("total instances    : ${files.size}")
        println("parsed             : $parsed (${pct(parsed, files.size)})")
        if (solve) {
            println("  solved           : $solved (sat/unsat/opt within ${timeMs}ms)")
            println("  unknown          : $unknown (budget exhausted)")
        }
        println("unsupported        : $unsupported")
        println("parse-error        : $parseError")
        println("timeout (> ${instanceMs}ms) : $timeout")
        println("skipped (> ${maxBytes / 1_000_000}MB)   : $skipped")
        if (reasons.isNotEmpty()) {
            println("--- top unsupported constructs / parse errors ---")
            reasons.entries.sortedByDescending { it.value }.take(
                40,
            ).forEach { (k, v) -> println("  %5d  %s".format(Locale.ROOT, v, k)) }
        }
    }

    /** Per-instance outcome, tallied by [report]. */
    private sealed interface R {
        data object Skip : R // too large
        data object Parsed : R // parsed (solve disabled)
        data object Solved : R
        data object Unknown : R
        data class Unsupported(val reason: String) : R
        data class ParseError(val reason: String) : R
    }

    /** Read + parse + (optionally) solve a single instance, classifying the outcome. */
    @Suppress("TooGenericExceptionCaught")
    private fun processOne(f: File, parse: (String) -> Parsed): R {
        val text = try {
            readInstance(
                f,
            )
        } catch (e: Exception) {
            return R.ParseError("READ-ERROR: " + e.message?.take(40).orEmpty())
        }
            ?: return R.Skip
        val inst = try {
            parse(text)
        } catch (e: UnsupportedXcsp3Exception) {
            return R.Unsupported(reason(e.message))
        } catch (e: UnsupportedSmtException) {
            return R.Unsupported(reason(e.message))
        } catch (e: Throwable) {
            return R.ParseError("PARSE-ERROR: " + (e.message?.take(40) ?: e::class.simpleName))
        }
        if (!solve) return R.Parsed
        return when (attemptSolve(inst)) {
            Outcome.SOLVED -> R.Solved
            Outcome.UNKNOWN -> R.Unknown
        }
    }

    /** Run [work] on a daemon thread, returning its result or `null` if it exceeds [ms]
     *  (the thread is abandoned — fine for a one-shot coverage scan). */
    @Suppress("TooGenericExceptionCaught")
    private fun runBounded(ms: Long, work: () -> R): R? {
        var res: R? = null
        val t = Thread {
            res = try {
                work()
            } catch (e: Throwable) {
                R.ParseError(
                    "ERROR: " + e.message?.take(40).orEmpty(),
                )
            }
        }
        t.isDaemon = true
        t.start()
        t.join(ms)
        return if (t.isAlive) null else res
    }

    private enum class Outcome { SOLVED, UNKNOWN }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun attemptSolve(p: Parsed): Outcome = try {
        val params = BacktrackParams(cancellation = Cancellation.after(timeMs.milliseconds))
        val solver = BacktrackSolver(p.problem)
        if (p.objective != null) {
            when (solver.minimize(p.objective, params)) {
                is MinimizeResult.Optimal, is MinimizeResult.Infeasible -> Outcome.SOLVED
                else -> Outcome.UNKNOWN
            }
        } else {
            when (solver.solve(params)) {
                is SolveResult.Sat, is SolveResult.Unsat -> Outcome.SOLVED
                is SolveResult.Unknown -> Outcome.UNKNOWN
            }
        }
    } catch (e: Throwable) {
        Outcome.UNKNOWN
    }

    /** Read an instance, decompressing `.lzma` / `.xz` on the fly via the `xz` CLI. Returns
     *  `null` (skip) when the decompressed size would exceed [maxBytes] — read is bounded, so
     *  a giant instance costs at most [maxBytes] of memory rather than its full expansion. */
    private fun readInstance(f: File): String? = when (f.extension.lowercase()) {
        "lzma", "xz" -> {
            val p = ProcessBuilder("xz", "-dc", f.absolutePath).redirectErrorStream(false).start()
            try {
                boundedRead(p.inputStream, maxBytes).also { p.destroyForcibly() }
            } finally {
                p.destroyForcibly()
            }
        }

        else -> if (f.length() > maxBytes) null else f.readText()
    }

    /** Read at most [cap] bytes; return null if the stream has more (i.e. instance too big). */
    private fun boundedRead(input: InputStream, cap: Long): String? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(1 shl 16)
        var total = 0L
        input.use {
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                total += n
                if (total > cap) return null
                buf.write(chunk, 0, n)
            }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun reason(msg: String?): String = (msg ?: "unknown").substringAfter(": ").take(60)
    private fun pct(n: Int, d: Int): String = if (d == 0) "0%" else "%.1f%%".format(Locale.ROOT, n * 100.0 / d)
}
