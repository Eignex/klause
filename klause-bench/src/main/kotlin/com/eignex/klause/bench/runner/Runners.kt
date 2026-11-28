package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.ProblemRef

/** Picks the runner for a catalog problem: MiniZinc models compile through `minizinc`, the rest run in-process. */
object Runners {
    private val miniZinc = MiniZincRunner()
    private val exactMiniZinc = MiniZincRunner(exactFloats = true)

    internal fun runnerFor(ref: ProblemRef, exact: Boolean = false): Runner = when {
        miniZinc.supports(ref) -> if (exact) exactMiniZinc else miniZinc
        InProcessRunner.supports(ref) -> InProcessRunner
        else -> error("${ref.name}: no runner supports format ${ref.format}")
    }

    internal fun resolve(ref: ProblemRef, exact: Boolean = false): ResolvedProblem = runnerFor(ref, exact).resolve(ref)
}
