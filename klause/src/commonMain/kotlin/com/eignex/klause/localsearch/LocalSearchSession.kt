package com.eignex.klause.localsearch

import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchStream
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.StatelessSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult

/**
 * A scoped local-search session retaining learned factor weights across fresh searches.
 * Weights sync into each search at initialization and out at every published sample or incumbent,
 * and at search completion. Closing an early stream preserves its latest published warm state.
 *
 * Single-threaded: an active stream or resumable handle excludes scope changes, [reset] and other
 * searches. Local-search strategy and restart policy also require exclusive use across sessions
 * sharing the same solver. Fresh calls retain weights; resumable slices additionally retain the
 * assignment, RNG and restart progress.
 */
class LocalSearchSession(override val solver: LocalSearchSolver) : StatelessSession<LocalSearchParams>(solver) {
    private val warm = WarmState()

    /** Discard learned state; requires an open, idle session. */
    fun reset() {
        ensureAvailable()
        warm.reset()
    }

    internal val warmState: WarmState get() = warm
    internal val warmStateView: WarmState get() = warm

    override fun resumableSolve(params: LocalSearchParams): ResumableSolve = checkNotNull(super.resumableSolve(params))

    override fun resumable(objective: LinearObjective, params: LocalSearchParams): ResumableSearch =
        checkNotNull(super.resumable(objective, params))

    internal override fun solveScoped(params: LocalSearchParams): SolveResult = solver.engine.solve(params, warm)
    internal override fun resumableSolveScoped(params: LocalSearchParams): ResumableSolve =
        solver.engine.resumableSolve(params, warm)
    internal override fun resumableScoped(objective: LinearObjective, params: LocalSearchParams): ResumableSearch =
        solver.engine.resumable(objective, params, warm)
    internal override fun samplesScoped(params: LocalSearchParams): Sequence<Sample> = solver.engine.samples(params, warm)
    internal override fun enumerateScoped(params: LocalSearchParams): Sequence<Sample> = solver.engine.samples(params, warm)
    internal override fun minimizeScoped(objective: LinearObjective, params: LocalSearchParams): MinimizeResult =
        solver.engine.improvements(objective, params, warm).last()
    internal override fun improvementsScoped(objective: LinearObjective, params: LocalSearchParams): Sequence<MinimizeResult> =
        solver.engine.improvements(objective, params, warm)
    internal override fun openSamplesScoped(params: LocalSearchParams): SearchStream<Sample> =
        solver.engine.openSamples(params, warm)
    internal override fun openQualitySamplesScoped(config: SamplingConfig, params: LocalSearchParams): SearchStream<Sample> =
        solver.engine.openSamples(config, params, warm)
    internal override fun openEnumerateScoped(params: LocalSearchParams): SearchStream<Sample> =
        solver.engine.openSamples(params, warm)
    internal override fun openImprovementsScoped(objective: LinearObjective, params: LocalSearchParams): SearchStream<MinimizeResult> =
        solver.engine.openImprovements(objective, params, warm)
}
