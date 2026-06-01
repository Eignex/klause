package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.AnytimeMetric
import com.eignex.klause.bench.metric.CompileAuditMetric
import com.eignex.klause.bench.metric.CompletenessMetric
import com.eignex.klause.bench.metric.CoverageMetric
import com.eignex.klause.bench.metric.ParityMetric
import com.eignex.klause.bench.metric.SearchEffortMetric
import com.eignex.klause.bench.metric.TimeMetric
import com.eignex.klause.bench.metric.TuningMetric
import com.eignex.klause.bench.metric.UniformnessMetric
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.solver.Backend

/**
 * Runs a [MetricKind] over a concrete set of [ProblemRef]s. The single dispatch point shared
 * by predefined [Target]s and the ad-hoc selection CLI — so both reach every metric the same
 * way. Differential metrics (parity/anytime) take a reference backend; the rest ignore it.
 */
object MetricRunner {
    fun run(metric: MetricKind, refs: List<ProblemRef>, budget: Budget, reference: Backend?) {
        when (metric) {
            MetricKind.PARITY -> ParityMetric.run(BenchLoad.resolveRefs(refs), budget, reference ?: Backend.CHOCO)
            MetricKind.ANYTIME -> AnytimeMetric.run(BenchLoad.resolveRefs(refs), budget, reference ?: Backend.ORTOOLS)
            MetricKind.TUNING -> TuningMetric.run(BenchLoad.resolveRefs(refs), budget)
            MetricKind.SEARCH -> SearchEffortMetric.run(BenchLoad.resolveRefs(refs), budget)
            MetricKind.COVERAGE -> CoverageMetric.run(refs)
            MetricKind.AUDIT -> CompileAuditMetric.run(refs)
            MetricKind.VERIFY, MetricKind.TIME, MetricKind.UNIFORMNESS, MetricKind.COMPLETENESS -> {
                val corpus = BenchLoad.loadAndVerifyRefs(refs)
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
