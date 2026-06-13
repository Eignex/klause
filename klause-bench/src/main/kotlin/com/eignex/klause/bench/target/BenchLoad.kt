package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.InProcessRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners

/** Corpus resolution for the metrics. Solving correctness is the responsibility of klause's own
 *  test suite (brute-force oracles etc.), not the bench — so there is no cross-engine gate here. */
internal object BenchLoad {
    /** Resolve every problem in the given suites with the appropriate runner (MiniZinc compile
     *  or in-process). */
    @Suppress("SpreadOperator")
    fun resolve(suiteIds: List<String>): List<ResolvedProblem> =
        Catalog.problems(*suiteIds.toTypedArray()).map { Runners.resolve(it) }

    /** Resolve every ref with the appropriate runner. */
    fun resolveRefs(refs: List<ProblemRef>): List<ResolvedProblem> = refs.map { Runners.resolve(it) }

    /** The in-process, feasible-expected subset the sampling metrics (uniformness/completeness) run
     *  on. Non-in-process formats (e.g. MiniZinc) and infeasible-expected problems are dropped. */
    fun feasibleInProcessRefs(refs: List<ProblemRef>): List<ResolvedProblem> =
        refs.filter { InProcessRunner.supports(it) }
            .map { InProcessRunner.resolve(it) }
            .filter { it.ref.expected.expectsSat }
}
