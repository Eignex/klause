package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult

/**
 * The common, **blocking** interface of the two portfolio executors — the parallel `Portfolio`
 * (jvm+native, real threads) and the single-core [SequentialPortfolio] (bandit-scheduled segments).
 * A caller selects one by [PortfolioScenario.threads] and then invokes `solve`/`minimize`
 * identically, regardless of which it got. Coroutine-free: both are plain blocking calls.
 */
interface PortfolioExecutor : AutoCloseable {
    /** Solve (satisfaction), honouring [cancellation]. */
    fun solve(cancellation: Cancellation = Cancellation.Never): SolveResult

    /** Branch-and-bound minimisation, honouring [cancellation]. */
    fun minimize(cancellation: Cancellation = Cancellation.Never): MinimizeResult
}
