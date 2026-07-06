package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

/**
 * Content-addressed cache of [SolverInvocation.Result]s, keyed by
 * `sha256(model bytes + datafile bytes) · time-settings · solver+settings`. A run looks the key up
 * first: a hit replays the stored result with no subprocess, a miss invokes and stores. This is the
 * "known record of solved/presolved things" — reference baselines stay frozen across runs while
 * klause iterates (klause's key also folds in the klause-cli binary's mtime, so a rebuild
 * invalidates only klause's entries, not the references').
 *
 * Disable with `-Dklause.bench.cache=false`. Lives under `build/bench-cache/`.
 */
internal object BenchCache {
    private val enabled = System.getProperty("klause.bench.cache")?.toBoolean() ?: true
    private val dir by lazy { File("build/bench-cache").apply { mkdirs() } }

    /** Key for solving [ref] with [solver] (the settings-encoding label) under [budget]. */
    fun keyFor(ref: ProblemRef, solver: String, budget: Budget): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(CorpusFetcher.resolve(ref.source).readBytes())
        ref.data?.let { md.update(CorpusFetcher.resolve(it).readBytes()) }
        md.update("|$solver|t=${budget.timeoutMillis}".toByteArray())
        // klause iterates → invalidate its entries when the cli binary changes; references are external.
        if (solver.startsWith("klause")) {
            val bin = SolverInvocation.klauseCliBin()
            if (bin.exists()) md.update("|cli=${bin.lastModified()}".toByteArray())
        }
        return md.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    fun load(key: String): SolverInvocation.Result? {
        if (!enabled) return null
        val f = File(dir, "$key.json")
        if (!f.isFile) return null
        return runCatching { Reports.json.decodeFromString<SolverInvocation.Result>(f.readText()) }.getOrNull()
    }

    fun store(key: String, result: SolverInvocation.Result) {
        if (!enabled) return
        File(dir, "$key.json").writeText(Reports.json.encodeToString(result))
    }
}
