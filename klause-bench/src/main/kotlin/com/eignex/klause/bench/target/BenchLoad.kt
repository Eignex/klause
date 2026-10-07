package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners

/** Corpus resolution for the metrics. Solving correctness is the responsibility of klause's own
 *  test suite (brute-force oracles etc.), not the bench — so there is no cross-engine gate here. */
internal object BenchLoad {
    /** Resolve each ref with the appropriate runner (MiniZinc compile or in-process) as the sequence is read.
     *  A resolved model can hold its whole parsed problem, so a long selection resolved up front outgrows the
     *  heap; a subprocess solve needs only the instance it is running. An instance that fails to resolve (an
     *  unsupported front-end feature — common on in-progress competition corpora like XCSP3) is **skipped
     *  with a warning** rather than aborting the whole selection, so a bulk run covers everything that
     *  compiles today. */
    fun resolveLazily(refs: List<ProblemRef>, exact: Boolean = false): Sequence<ResolvedProblem> =
        refs.asSequence().mapNotNull { ref ->
            runCatching { Runners.resolve(ref, exact) }
                .onFailure { println("[load] skipped ${ref.name}: ${it.message.orEmpty().take(100)}") }
                .getOrNull()
        }
}
