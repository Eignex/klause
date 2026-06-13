package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.CompileAuditMetric
import com.eignex.klause.bench.metric.KlauseSearch
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.Profiler

/**
 * Runs a [MetricKind] over a concrete set of [ProblemRef]s — the single dispatch the ad-hoc
 * selection CLI reaches every metric through. Only [SolveMetric] takes a backend (`backend=`);
 * [CompileAuditMetric] ignores it.
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
        backend: String? = null,
        profile: ProfileConfig? = null,
        search: KlauseSearch? = null,
    ) {
        // SOLVE runs solvers as subprocesses (klause-cli / minizinc), which JFR on the bench JVM
        // can't see — so it profiles the klause engine IN-PROCESS itself (see SolveMetric); pass the
        // config straight through rather than wrapping the subprocess plumbing.
        if (metric == MetricKind.SOLVE) {
            SolveMetric.run(
                BenchLoad.resolveRefs(refs),
                budget,
                backend ?: SolverInvocation.KLAUSE,
                search ?: KlauseSearch(),
                profile,
            )
            return
        }
        // AUDIT is compile-only — there is no separate solve phase, so the scope=SOLVE/ALL split is
        // moot; profile the whole run when asked, else just run it.
        if (profile != null) Profiler.record(profile) { CompileAuditMetric.run(refs) } else CompileAuditMetric.run(refs)
    }
}
