package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

/**
 * Content-addressed cache of [SolverInvocation.Result]s, keyed by
 * `sha256(model bytes + datafile bytes) · time-settings · solver+settings`. A run looks the key up
 * first: a hit replays the stored result with no subprocess, a miss invokes and stores. This is the
 * "known record of solved/presolved things" — reference baselines stay frozen across runs while
 * klause iterates (klause's key also folds in the commit its cli was built from, so a new commit invalidates only
 * klause's entries and a rebuild of the same commit replays them). The bytes hashed are the decompressed ones, so
 * an instance keeps its key whether the corpus stores it plain or compressed.
 *
 * Disable with `-Dklause.bench.cache=false`. Lives under `build/bench-cache/`.
 */
internal object BenchCache {
    private val enabled = System.getProperty("klause.bench.cache")?.toBoolean() ?: true
    private val dir by lazy { File("build/bench-cache").apply { mkdirs() } }

    /** Key for solving [ref] with [solver] (the settings-encoding label) under [budget]. */
    fun keyFor(ref: ProblemRef, solver: String, budget: Budget): String =
        keyFor(CorpusFetcher.resolve(ref.source), ref.data?.let { CorpusFetcher.resolve(it) }, solver, budget)

    /** Key for solving the instance in [model] (plus its optional [data] file) with [solver] under [budget]. */
    fun keyFor(model: File, data: File?, solver: String, budget: Budget): String {
        val md = MessageDigest.getInstance("SHA-256")
        CorpusFiles.update(md, model)
        data?.let { CorpusFiles.update(md, it) }
        md.update("|$solver|t=${budget.timeoutMillis}".toByteArray())
        // klause iterates, references are external: a klause result belongs to the build that produced it.
        if (solver.startsWith("klause")) klauseBuild?.let { md.update("|cli=$it".toByteArray()) }
        return md.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    /**
     * The klause build a result belongs to: the commit, when the sources the cli is built from match it, so a rebuild
     * of the same commit replays its results; else the cli binary's modification time, so an edited tree never
     * borrows a committed build's results. Null without a built cli.
     */
    private val klauseBuild: String? by lazy {
        val bin = SolverInvocation.klauseCliBin()
        if (!bin.exists()) return@lazy null
        val sha = Reports.readGitSha()
        val changes = git(listOf("status", "--porcelain", "--untracked-files=no", "--") + BUILD_SOURCES)
        val clean = sha != null && changes?.isEmpty() == true
        if (clean) sha else "mtime-${bin.lastModified()}"
    }

    private fun git(args: List<String>): String? = runCatching {
        val proc = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0) out else null
    }.getOrNull()

    fun load(key: String): SolverInvocation.Result? {
        if (!enabled) return null
        val f = File(dir, "$key.json")
        if (!f.isFile) return null
        return runCatching { Reports.json.decodeFromString<SolverInvocation.Result>(f.readText()) }.getOrNull()
    }

    fun store(key: String, result: SolverInvocation.Result) {
        if (!enabled) return
        File(dir, "$key.json").writeText(Reports.json.encodeToString(compact(result)))
    }

    /** [result] with its model lines dropped when its output is too large to keep: a solver can print gigabytes
     *  of `v` lines on a large instance, while the status, objective and statistics lines are what a replay reads. */
    internal fun compact(result: SolverInvocation.Result): SolverInvocation.Result =
        if (result.rawOutput.length <= MAX_STORED_OUTPUT_CHARS) {
            result
        } else {
            val kept = result.rawOutput.lineSequence().filterNot { it.startsWith("v ") }
            result.copy(rawOutput = kept.joinToString("\n"))
        }
}

private const val MAX_STORED_OUTPUT_CHARS = 8 * 1024 * 1024

// The paths a klause-cli build reads. A change anywhere else, a regenerated reference table say, leaves results valid.
private val BUILD_SOURCES = listOf(
    "klause",
    "klause-cli",
    "klause-mzn-lib",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle",
    "gradle.properties",
)
