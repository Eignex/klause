package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.Agreement
import com.eignex.klause.bench.metric.Verifier
import com.eignex.klause.bench.runner.InProcessRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.solver.SolveResult

/** Resolved + cross-checked corpus, split into the full verify set and the benchable
 *  (feasible-expected) subset the measurement metrics run on. */
data class LoadedCorpus(
    val verifyEntries: List<ResolvedProblem>,
    val benchEntries: List<ResolvedProblem>,
)

/**
 * Resolves the in-process problems of the given suites and runs [Verifier] across them as a
 * correctness gate (mirrors the legacy `BenchHarness.loadAndVerify`). Problems whose format
 * is not in-process (e.g. MiniZinc) are skipped here — they belong to the phase-2 runners.
 */
object BenchLoad {
    /** Resolve every problem in the given suites with the appropriate runner (MiniZinc compile
     *  or in-process), without the cross-backend verify gate. Used by differential metrics
     *  (parity) that *are* the comparison. */
    fun resolve(suiteIds: List<String>): List<ResolvedProblem> =
        Catalog.problems(*suiteIds.toTypedArray()).map { Runners.resolve(it) }

    fun loadAndVerify(suiteIds: List<String>, quiet: Boolean = false): LoadedCorpus =
        loadAndVerifyRefs(Catalog.problems(*suiteIds.toTypedArray()), quiet)

    /** Resolve every ref with the appropriate runner (no verify gate). */
    fun resolveRefs(refs: List<ProblemRef>): List<ResolvedProblem> = refs.map { Runners.resolve(it) }

    fun loadAndVerifyRefs(refs: List<ProblemRef>, quiet: Boolean = false): LoadedCorpus {
        val inProcess: List<ProblemRef> = refs.filter { InProcessRunner.supports(it) }
        val resolved = inProcess.map { InProcessRunner.resolve(it) }
        val benchEntries = resolved.filter { it.ref.expected.expectsSat }

        if (!quiet) {
            println("=== verification (${resolved.size} in-process entries) ===")
        }
        var disagreements = 0
        for (entry in resolved) {
            val report = Verifier.verify(entry.problem)
            if (!quiet) {
                val verdicts = report.verdicts.entries.joinToString(", ") { "${it.key}=${formatVerdict(it.value)}" }
                val samples = report.sampleChecks.entries.joinToString(", ") {
                    "${it.key}=${it.value.count { c -> c.satisfies }}/${it.value.size}"
                }
                println("[${entry.name}] agreement=${report.agreement} verdicts={$verdicts} samples-ok={$samples}")
            }
            if (report.agreement == Agreement.Disagree) disagreements++
            require(report.allSamplesSatisfy) {
                "${entry.name}: at least one backend produced a sample that does not satisfy the problem"
            }
        }
        if (disagreements > 0) error("$disagreements entries disagreed across backends")
        return LoadedCorpus(resolved, benchEntries)
    }

    private fun formatVerdict(v: SolveResult): String = when (v) {
        is SolveResult.Sat -> "Sat"
        is SolveResult.Unsat -> "Unsat"
        is SolveResult.Unknown -> "Unknown"
    }
}
