package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.ProblemRef

/**
 * Picks the right [Runner] for a [ProblemRef]: [MiniZincRunner] for MiniZinc models (which
 * need the `minizinc` compile step), [InProcessRunner] for everything else. Solving the
 * resolved [ResolvedProblem] is uniform afterwards, so callers depend only on this dispatch.
 */
object Runners {
    private val miniZinc = MiniZincRunner()

    internal fun runnerFor(ref: ProblemRef): Runner = when {
        miniZinc.supports(ref) -> miniZinc
        InProcessRunner.supports(ref) -> InProcessRunner
        else -> error("${ref.name}: no runner supports format ${ref.format}")
    }

    internal fun resolve(ref: ProblemRef): ResolvedProblem = runnerFor(ref).resolve(ref)
}
