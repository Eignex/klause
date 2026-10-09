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

// Content-addressed subprocess results. Installed artifacts identify klause's executed build.
// Disable with -Dklause.bench.cache=false. Lives under build/bench-cache/.
internal object BenchCache {
    private val enabled = System.getProperty("klause.bench.cache")?.toBoolean() ?: true
    private val dir by lazy { File("build/bench-cache").apply { mkdirs() } }

    /** Key for solving [ref] with [solver] (the settings-encoding label) under [budget]. */
    fun keyFor(
        ref: ProblemRef,
        solver: String,
        budget: Budget,
        provenance: BuildProvenance? = if (solver.startsWith("klause")) InstalledBuild.current else null,
        settings: SolverInvocation.Settings? = null,
        validationPolicy: String = REPORTED_RESULT_POLICY,
    ): String = keyFor(
        CorpusFetcher.resolve(ref.source),
        ref.data?.let { CorpusFetcher.resolve(it) },
        solver,
        budget,
        provenance,
        settings,
        validationPolicy,
    )

    /** Key for solving the instance in [model] (plus its optional [data] file) with [solver] under [budget]. */
    fun keyFor(
        model: File,
        data: File?,
        solver: String,
        budget: Budget,
        provenance: BuildProvenance? = if (solver.startsWith("klause")) InstalledBuild.current else null,
        settings: SolverInvocation.Settings? = null,
        validationPolicy: String = REPORTED_RESULT_POLICY,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        CorpusFiles.update(md, model)
        data?.let { CorpusFiles.update(md, it) }
        md.update("|$solver|t=${budget.timeoutMillis}".toByteArray())
        provenance?.let { md.update("|build=${it.fingerprint}".toByteArray()) }
        settings?.let { md.update(Reports.json.encodeToString(it).toByteArray()) }
        md.update("|validation=$validationPolicy|timing=elapsed-v1".toByteArray())
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
