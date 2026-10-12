package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners

/** Lazy corpus resolution for benchmark metrics. */
internal object BenchLoad {
    /**
     * Resolve inputs lazily so only the current instance occupies the heap.
     * Resolution failures are skipped with a warning so the remaining selection runs.
     */
    fun resolveLazily(refs: List<ProblemRef>, exact: Boolean = false): Sequence<ResolvedProblem> =
        refs.asSequence().mapNotNull { ref ->
            runCatching { Runners.resolve(ref, exact) }
                .onFailure { println("[load] skipped ${ref.name}: ${it.message.orEmpty().take(100)}") }
                .getOrNull()
        }
}
