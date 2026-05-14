package com.eignex.klause.bench

import com.eignex.klause.solver.SolveResult
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared loading + cross-backend verification scaffolding used by every bench `main`.
 * Each [com.eignex.klause.bench.TimeBenchMain] / `UniformnessBenchMain` /
 * `CompletenessBenchMain` calls [loadAndVerify] once at startup so all three operate on
 * the same set of cross-checked problems.
 */
object BenchHarness {

    private val loaders: List<ProblemLoader> = listOf(
        DimacsLoader, OpbLoader, JsonSchemaLoader, FlatZincLoader, SatlibLoader,
    )

    data class LoadedEntries(
        val verifyEntries: List<Portfolio.Entry>,
        val benchEntries: List<Portfolio.Entry>,
        val externalsByFormat: List<Pair<ProblemLoader, List<Portfolio.Entry>>>,
    )

    /** Load every bundled + discovered entry and run [Verifier] across them. Exits the
     *  process if any portfolio entry disagrees across backends. Returns the SAT-only
     *  subset suitable for benching. */
    fun loadAndVerify(quiet: Boolean = false): LoadedEntries {
        val loaded = loaders.map { it to it.loadBundled() }
        val externals = loaded.flatMap { it.second }
        val verifyEntries = Portfolio.all + externals
        val benchEntries = Portfolio.sat + externals.filter { it.expectedSat }

        if (!quiet) {
            val header = buildString {
                append("=== verification (${verifyEntries.size} entries: ")
                append("${Portfolio.all.size} hard-coded")
                for ((loader, entries) in loaded) {
                    append(", ${entries.size} ${loader.format}")
                }
                append(") ===")
            }
            println(header)
        }
        var disagreements = 0
        for (entry in verifyEntries) {
            val report = Verifier.verify(entry.problem)
            if (!quiet) {
                val verdicts = report.verdicts.entries.joinToString(", ") {
                    "${it.key}=${formatVerdict(it.value)}"
                }
                val sampleSummary = report.sampleChecks.entries.joinToString(", ") {
                    "${it.key}=${it.value.count { c -> c.satisfies }}/${it.value.size}"
                }
                println("[${entry.name}] agreement=${report.agreement} verdicts={$verdicts} samples-ok={$sampleSummary}")
            }
            if (report.agreement == Agreement.Disagree) disagreements++
            require(report.allSamplesSatisfy) {
                "${entry.name}: at least one backend produced a sample that does not satisfy the problem"
            }
        }
        if (disagreements > 0) error("$disagreements portfolio entries disagreed across backends")
        return LoadedEntries(verifyEntries, benchEntries, loaded)
    }

    private fun formatVerdict(v: SolveResult): String = when (v) {
        is SolveResult.Sat -> "Sat"
        SolveResult.Unsat -> "Unsat"
        SolveResult.Unknown -> "Unknown"
    }

    val json: Json = Json { prettyPrint = true; encodeDefaults = true }

    inline fun <reified T> writeJson(path: String, value: T) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(value))
        println()
        println("wrote $path")
    }

    fun readGitSha(): String? = runCatching {
        val proc = ProcessBuilder("git", "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
    }.getOrNull()

    fun formatNs(ns: Long): String = when {
        ns < 1_000 -> "${ns}ns"
        ns < 1_000_000 -> "${ns / 1_000}µs"
        ns < 1_000_000_000 -> "${ns / 1_000_000}ms"
        else -> "${ns / 1_000_000_000}s"
    }
}
