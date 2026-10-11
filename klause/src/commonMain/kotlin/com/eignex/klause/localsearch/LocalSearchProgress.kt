package com.eignex.klause.localsearch

import com.eignex.klause.solver.result.SolveStatsSink

internal class LocalSearchProgress(
    private val state: LocalSearchState,
    private val params: LocalSearchParams,
    private val warm: WarmState?,
    private val sink: SolveStatsSink?,
) {
    private val residuals = if (params.reportResiduals) LocalSearchResidualRecorder() else null
    private var charged = 0L

    fun observeResidual() {
        residuals?.observe(state)
    }

    fun publishResidual() {
        if (sink != null) sink.ls.bestResidual = residuals?.best
    }

    fun charge(moves: Long) {
        params.nodeBudget?.spendMoves(moves - charged)
        charged = moves
    }

    fun captureWarm() {
        warm?.captureFrom(state)
    }

    fun satisfaction(moves: Long, restarts: Long, bestCost: Long, everFeasible: Boolean) {
        observeResidual()
        publishResidual()
        charge(moves)
        if (!everFeasible) {
            sink?.ls?.recordWork(moves = moves, restarts = restarts, stalls = 0L)
            sink?.ls?.recordIncumbent(objective = Double.NaN, violation = bestCost.toDouble(), foundAtMs = -1L)
        }
        captureWarm()
    }

    @Suppress("LongParameterList")
    fun optimization(
        moves: Long,
        restarts: Long,
        stalls: Long,
        bestObjective: Double,
        hasIncumbent: Boolean,
        bestCost: Long,
        foundAtMs: Long,
        checkpoint: LocalSearchCheckpoint?,
    ) {
        val live = checkNotNull(sink)
        observeResidual()
        publishResidual()
        checkpoint?.shouldPause(moves)
        live.ls.recordWork(moves = moves, restarts = restarts, stalls = stalls)
        charge(moves)
        captureWarm()
        live.ls.recordIncumbent(
            objective = bestObjective.takeIf { hasIncumbent } ?: Double.NaN,
            violation = if (hasIncumbent) 0.0 else minOf(bestCost, state.cost).toDouble(),
            foundAtMs = foundAtMs,
        )
    }
}
