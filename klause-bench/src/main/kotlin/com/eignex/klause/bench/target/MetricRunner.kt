package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.AnytimeMetric
import com.eignex.klause.bench.metric.CompileAuditMetric
import com.eignex.klause.bench.metric.CompletenessMetric
import com.eignex.klause.bench.metric.CoverageMetric
import com.eignex.klause.bench.metric.ParityMetric
import com.eignex.klause.bench.metric.PortfolioCreditMetric
import com.eignex.klause.bench.metric.SearchEffortMetric
import com.eignex.klause.bench.metric.TimeMetric
import com.eignex.klause.bench.metric.TuningMetric
import com.eignex.klause.bench.metric.UniformnessMetric
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.ProfileScope
import com.eignex.klause.bench.tools.Profiler

/**
 * Runs a [MetricKind] over a concrete set of [ProblemRef]s. The single dispatch point shared
 * by predefined [Target]s and the ad-hoc selection CLI — so both reach every metric the same
 * way. Differential metrics (parity/anytime) take a reference backend; the rest ignore it.
 *
 * When [profile] is set the run is recorded with the in-harness JFR [Profiler]. `scope=ALL`
 * wraps the whole run (resolve + solve); `scope=SOLVE` wraps only the measurement, so the
 * resolve step (corpus fetch + parse + MiniZinc compile) is excluded from the profile.
 *
 * [wantCop], when set, keeps only constraint-optimization problems (`true`) or only satisfaction
 * problems (`false`), judged *exactly* from each resolved problem's objective (so a MiniZinc
 * `solve minimize/maximize`, an OPB `min:`, etc. classify correctly). The filter runs after
 * resolution, before the measurement — so it costs a resolve, but never a second one.
 */
internal object MetricRunner {
    fun run(
        metric: MetricKind,
        refs: List<ProblemRef>,
        budget: Budget,
        reference: Backend?,
        profile: ProfileConfig? = null,
        wantCop: Boolean? = null,
    ) {
        if (profile != null && profile.scope == ProfileScope.ALL) {
            Profiler.record(profile) { dispatch(metric, refs, budget, reference, solveProfile = null, wantCop) }
        } else {
            dispatch(metric, refs, budget, reference, solveProfile = profile, wantCop)
        }
    }

    /** Keep only problems whose optimization-ness matches [wantCop] (null ⇒ keep all). A problem
     *  is a COP iff it resolved with an objective. */
    private fun List<ResolvedProblem>.byKind(wantCop: Boolean?): List<ResolvedProblem> =
        if (wantCop == null) this else filter { (it.objective != null) == wantCop }

    /** [solveProfile], when set, profiles just the measurement of each metric — resolution has
     *  already happened by then, so parsing/setup is discounted. */
    @Suppress("CyclomaticComplexMethod")
    private fun dispatch(
        metric: MetricKind,
        refs: List<ProblemRef>,
        budget: Budget,
        reference: Backend?,
        solveProfile: ProfileConfig?,
        wantCop: Boolean?,
    ) {
        fun <T> solve(block: () -> T): T = if (solveProfile != null) Profiler.record(solveProfile, block) else block()
        when (metric) {
            MetricKind.PARITY -> {
                val resolved = BenchLoad.resolveRefs(refs).byKind(wantCop)
                solve { ParityMetric.run(resolved, budget, reference ?: Backend.CHOCO) }
            }

            MetricKind.ANYTIME -> {
                val resolved = BenchLoad.resolveRefs(refs).byKind(wantCop)
                solve { AnytimeMetric.run(resolved, budget, reference ?: Backend.ORTOOLS) }
            }

            MetricKind.TUNING -> {
                val resolved = BenchLoad.resolveRefs(refs).byKind(wantCop)
                solve { TuningMetric.run(resolved, budget) }
            }

            MetricKind.CREDIT -> {
                val resolved = BenchLoad.resolveRefs(refs).byKind(wantCop)
                solve { PortfolioCreditMetric.run(resolved, budget) }
            }

            MetricKind.SEARCH -> {
                val resolved = BenchLoad.resolveRefs(refs).byKind(wantCop)
                solve { SearchEffortMetric.run(resolved, budget) }
            }

            MetricKind.COVERAGE -> solve { CoverageMetric.run(refs) }

            MetricKind.AUDIT -> solve { CompileAuditMetric.run(refs) }

            MetricKind.VERIFY, MetricKind.TIME, MetricKind.UNIFORMNESS, MetricKind.COMPLETENESS -> {
                val corpus = BenchLoad.loadAndVerifyRefs(refs)
                val verifyEntries = corpus.verifyEntries.byKind(wantCop)
                val benchEntries = corpus.benchEntries.byKind(wantCop)
                solve {
                    when (metric) {
                        MetricKind.VERIFY -> println("\nverification passed for ${verifyEntries.size} entries")
                        MetricKind.TIME -> TimeMetric.run(benchEntries)
                        MetricKind.UNIFORMNESS -> UniformnessMetric.run(benchEntries)
                        MetricKind.COMPLETENESS -> CompletenessMetric.run(benchEntries)
                        else -> error("unreachable")
                    }
                }
            }
        }
    }
}
