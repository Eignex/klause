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
 * The `kind=cop|csp` selection filter is applied earlier, during selection
 * (`com.eignex.klause.bench.source.ProblemKind`), so it is not a concern here.
 */
internal object MetricRunner {
    fun run(
        metric: MetricKind,
        refs: List<ProblemRef>,
        budget: Budget,
        reference: Backend?,
        profile: ProfileConfig? = null,
    ) {
        if (profile != null && profile.scope == ProfileScope.ALL) {
            Profiler.record(profile) { dispatch(metric, refs, budget, reference, solveProfile = null) }
        } else {
            dispatch(metric, refs, budget, reference, solveProfile = profile)
        }
    }

    /** [solveProfile], when set, profiles just the measurement of each metric — resolution has
     *  already happened by then, so parsing/setup is discounted. */
    private fun dispatch(
        metric: MetricKind,
        refs: List<ProblemRef>,
        budget: Budget,
        reference: Backend?,
        solveProfile: ProfileConfig?,
    ) {
        fun <T> solve(block: () -> T): T = if (solveProfile != null) Profiler.record(solveProfile, block) else block()
        when (metric) {
            MetricKind.PARITY -> {
                val resolved = BenchLoad.resolveRefs(refs)
                solve { ParityMetric.run(resolved, budget, reference ?: Backend.CHOCO) }
            }

            MetricKind.ANYTIME -> {
                val resolved = BenchLoad.resolveRefs(refs)
                solve { AnytimeMetric.run(resolved, budget, reference ?: Backend.ORTOOLS) }
            }

            MetricKind.TUNING -> {
                val resolved = BenchLoad.resolveRefs(refs)
                solve { TuningMetric.run(resolved, budget) }
            }

            MetricKind.CREDIT -> {
                val resolved = BenchLoad.resolveRefs(refs)
                solve { PortfolioCreditMetric.run(resolved, budget) }
            }

            MetricKind.SEARCH -> {
                val resolved = BenchLoad.resolveRefs(refs)
                solve { SearchEffortMetric.run(resolved, budget) }
            }

            MetricKind.COVERAGE -> solve { CoverageMetric.run(refs) }

            MetricKind.AUDIT -> solve { CompileAuditMetric.run(refs) }

            MetricKind.VERIFY, MetricKind.TIME, MetricKind.UNIFORMNESS, MetricKind.COMPLETENESS -> {
                val corpus = BenchLoad.loadAndVerifyRefs(refs)
                solve {
                    when (metric) {
                        MetricKind.VERIFY -> println("\nverification passed for ${corpus.verifyEntries.size} entries")
                        MetricKind.TIME -> TimeMetric.run(corpus.benchEntries)
                        MetricKind.UNIFORMNESS -> UniformnessMetric.run(corpus.benchEntries)
                        MetricKind.COMPLETENESS -> CompletenessMetric.run(corpus.benchEntries)
                        else -> error("unreachable")
                    }
                }
            }
        }
    }
}
