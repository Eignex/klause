package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners

/** Corpus resolution for the metrics. Solving correctness is the responsibility of klause's own
 *  test suite (brute-force oracles etc.), not the bench — so there is no cross-engine gate here. */
internal object BenchLoad {
    /** Resolve every ref with the appropriate runner (MiniZinc compile or in-process). */
    fun resolveRefs(refs: List<ProblemRef>): List<ResolvedProblem> = refs.map { Runners.resolve(it) }
}
