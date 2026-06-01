package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult

/**
 * Stateful wrapper around a [LocalSearchSolver] that persists per-strategy learned state
 * (currently DDFW-style factor weights) across calls. The plain [LocalSearchSolver] keeps
 * its per-draw isolation property — concurrent callers don't interfere — by being
 * stateless across calls. Using a session is the opt-in path for callers that want
 * weights / heuristics to survive a `sample` / `solve` / `minimize` boundary.
 *
 * Implements [Session] so it slots into `solver.session()` like any other backend's
 * stateful handle; on top of the standard `solve` / `samples` / `enumerate` it offers
 * a `minimize` overload for the optimisation path (not part of the base interface
 * because not every backend's [com.eignex.klause.solver.Solver] is an
 * [com.eignex.klause.solver.Optimizer]).
 *
 * Sessions are **not thread-safe**: one consumer per session. The same underlying
 * [solver] can be shared by multiple sessions if each session is used from one thread.
 *
 * Sync points:
 *  - Sync-in: at the start of each call, the warm state is copied into the new
 *    [LocalSearchState.factorWeights] (only if size matches `problem.numFactors`).
 *  - Sync-out: at the end of the search loop (or when a streaming sequence completes
 *    naturally / its iterator is cancelled). Sequences abandoned mid-iteration may not
 *    sync — accepted loss; the next call still starts from the previous capture.
 */
class LocalSearchSession(override val solver: LocalSearchSolver) : Session<LocalSearchParams> {

    private val warm: WarmState = WarmState()
    private val stack: ArrayDeque<Assumptions> = ArrayDeque()

    override val depth: Int get() = stack.size

    override fun push(assumptions: Assumptions) {
        stack.addLast(assumptions)
    }

    override fun pop() {
        require(stack.isNotEmpty()) { "Session.pop on an empty assumption stack" }
        stack.removeLast()
    }

    /** Discard all warm state. The next call starts from strategy defaults. */
    fun reset() = warm.reset()

    /** Test-only window into the warm state. */
    internal val warmState: WarmState get() = warm

    /** Read-only handle for cooperating components (e.g. ALNS destroy operators that
     *  read [WarmState.activityRecency]). External callers must not mutate the warm
     *  state directly — use [reset] to clear it. */
    internal val warmStateView: WarmState get() = warm

    override fun solve(params: LocalSearchParams): SolveResult = solver.solveInternal(applyStack(params), warm)

    override fun samples(params: LocalSearchParams): Sequence<Sample> = solver.samplesInternal(applyStack(params), warm)

    override fun enumerate(params: LocalSearchParams): Sequence<Sample> =
        solver.enumerateInternal(applyStack(params), warm)

    /** Optimisation entry point — overrides [Session.minimize] with warm-start support. */
    override fun minimize(objective: Objective, params: LocalSearchParams): MinimizeResult =
        solver.minimizeInternal(objective, applyStack(params), warm)

    /** Streaming optimisation — yields each new incumbent then a terminal verdict.
     *  Mirrors [com.eignex.klause.solver.Optimizer.improvements]. */
    override fun improvements(objective: Objective, params: LocalSearchParams): Sequence<MinimizeResult> =
        solver.improvementsInternal(objective, applyStack(params), warm)

    private fun applyStack(params: LocalSearchParams): LocalSearchParams {
        if (stack.isEmpty()) return params
        var merged = params.assumptions
        for (a in stack) merged = merged.mergedWith(a)
        return params.copy(assumptions = merged)
    }
}
