package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners

/** Corpus resolution for the metrics. Solving correctness is the responsibility of klause's own
 *  test suite (brute-force oracles etc.), not the bench — so there is no cross-engine gate here. */
internal object BenchLoad {
    /** Resolve every ref with the appropriate runner (MiniZinc compile or in-process). An instance that
     *  fails to resolve (an unsupported front-end feature — common on in-progress competition corpora
     *  like XCSP3) is **skipped with a warning** rather than aborting the whole selection, so a bulk run
     *  covers everything that compiles today. The skipped set is printed so the gap stays visible. */
    fun resolveRefs(refs: List<ProblemRef>): List<ResolvedProblem> {
        val resolved = ArrayList<ResolvedProblem>(refs.size)
        val skipped = ArrayList<Pair<String, String>>()
        for (ref in refs) {
            runCatching { Runners.resolve(ref) }
                .onSuccess { resolved += it }
                .onFailure { skipped += ref.name to (it.message ?: it::class.simpleName ?: "resolve error") }
        }
        if (skipped.isNotEmpty()) {
            println("[load] skipped ${skipped.size}/${refs.size} unresolvable instance(s):")
            skipped.take(10).forEach { (name, why) -> println("  - $name: ${why.take(100)}") }
            if (skipped.size > 10) println("  … and ${skipped.size - 10} more")
        }
        return resolved
    }
}
