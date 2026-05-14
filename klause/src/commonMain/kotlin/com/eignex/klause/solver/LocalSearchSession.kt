package com.eignex.klause.solver

/**
 * Stateful wrapper around a [LocalSearchSolver] that persists per-strategy learned state
 * (currently DDFW-style factor weights) across calls. The plain [LocalSearchSolver] keeps
 * its per-draw isolation property — concurrent callers don't interfere — by being
 * stateless across calls. Using a session is the opt-in path for callers that want
 * weights/heuristics to survive a `sample` / `solve` / `minimize` boundary.
 *
 * Sessions are **not thread-safe**: one consumer per session. The same underlying
 * [solver] can be shared by multiple sessions if each session is used from one thread.
 *
 * Sync points:
 *  - Sync-in: at the start of each call, the warm state is copied into the new
 *    [SolverState.factorWeights] (only if size matches `problem.numFactors`).
 *  - Sync-out: at the end of the search loop (or when a streaming sequence completes
 *    naturally / its iterator is cancelled). Sequences abandoned mid-iteration may not
 *    sync — accepted loss; the next call still starts from the previous capture.
 */
class LocalSearchSession(val solver: LocalSearchSolver) {
    private val warm: WarmState = WarmState()

    /** Discard all warm state. The next call starts from strategy defaults. */
    fun reset() = warm.reset()

    /** Test-only window into the warm state. */
    internal val warmState: WarmState get() = warm

    fun solve(params: LocalSearchParams = LocalSearchParams()): SolveResult =
        solver.solveInternal(params, warm)

    fun sample(params: LocalSearchParams = LocalSearchParams()): Sample? =
        samples(params).firstOrNull()

    fun samples(params: LocalSearchParams = LocalSearchParams()): Sequence<Sample> =
        solver.samplesInternal(params, warm)

    fun enumerate(params: LocalSearchParams = LocalSearchParams()): Sequence<Sample> =
        solver.enumerateInternal(params, warm)

    fun minimize(objective: Objective, params: LocalSearchParams = LocalSearchParams()): Sample? =
        solver.minimizeInternal(objective, params, warm)

    fun minimizeAll(
        objective: Objective,
        params: LocalSearchParams = LocalSearchParams(),
        k: Int,
    ): Sequence<Sample> =
        solver.minimizeAllInternal(objective, params, k, warm)
}
