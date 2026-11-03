package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.util.Cancellation

internal data class LpDirectionalGain(val down: Double, val up: Double)

internal enum class LpProbeDecline { CANCELLED, ALLOWANCE, OWNER, BUSY, STALE_POINT, NO_OPTIMUM, SPLIT, CHILD }

internal data class LpProbeMetrics(
    val preparationWork: Long,
    val solveWork: Long,
    val completed: Int,
    val decline: LpProbeDecline? = null,
) {
    val work: Long get() = preparationWork + minOf(solveWork, Long.MAX_VALUE - preparationWork)
}

internal interface LpDirectionalProbeSolver {
    fun directionalGains(
        pointModel: LpModel,
        columns: IntArray,
        splits: LongArray,
        allowance: Long,
        token: Cancellation,
        observe: (LpProbeMetrics) -> Unit,
    ): List<LpDirectionalGain>?
}

internal class LpDirectionalProbes(
    private val state: LpExactState,
    private val warm: Basis,
    private val parentObjective: Double,
    private val factory: (LpModel, Long, Long) -> RevisedSimplex,
) {
    fun run(
        columns: IntArray,
        splits: LongArray,
        allowance: Long,
        token: Cancellation,
        observe: (LpProbeMetrics) -> Unit,
    ): List<LpDirectionalGain>? {
        if (allowance <= 0L || token() || columns.isEmpty() || columns.size > 2 || columns.size != splits.size ||
            columns.any { it !in 0 until state.model.n } || splits.any { it == Long.MAX_VALUE } ||
            (columns.size == 2 && columns[0] == columns[1])
        ) return null
        val budget = ProbeBudget(allowance)
        try {
            val gains = ArrayList<LpDirectionalGain>(columns.size)
            for (index in columns.indices) {
                val down = childGain(columns[index], splits[index], true, budget, 2 * columns.size, token)
                    ?: return null
                val up = childGain(columns[index], splits[index] + 1L, false, budget, 2 * columns.size, token)
                    ?: return null
                gains.add(LpDirectionalGain(down, up))
            }
            if (token()) {
                budget.decline = LpProbeDecline.CANCELLED
                return null
            }
            return gains
        } catch (_: LpProjectionStop) {
            budget.decline = if (token()) LpProbeDecline.CANCELLED else LpProbeDecline.ALLOWANCE
            return null
        } catch (_: BasisArithmeticException) {
            return null
        } catch (_: ArithmeticException) {
            return null
        } finally {
            observe(budget.metrics())
        }
    }

    private fun childGain(
        column: Int,
        endpoint: Long,
        upper: Boolean,
        budget: ProbeBudget,
        queries: Int,
        token: Cancellation,
    ): Double? {
        if (token()) return budget.stop(LpProbeDecline.CANCELLED)
        if (budget.remaining() <= 0L) return budget.stop(LpProbeDecline.ALLOWANCE)
        val available = budget.remaining()
        val projection = LpProjectionMeter(available,
            if (available > Long.MAX_VALUE / 16L) Long.MAX_VALUE else available * 16L, token)
        val child: LpModel?
        try {
            child = state.probeBound(column, upper, endpoint, projection)?.ownerWorkingModel(projection)
        } finally {
            budget.prepare(projection.work)
        }
        child ?: return null
        val workspace = 16L * child.numVars + 40L * child.m + 64L
        val scaling = LpScalingView.constructionWorkBound(child)
        if (workspace >= budget.remaining() || scaling >= budget.remaining() - workspace) {
            budget.decline = LpProbeDecline.ALLOWANCE
            return null
        }
        budget.prepare(workspace)
        budget.prepare(scaling)
        val offered = budget.remaining() / (queries - budget.completed)
        if (offered <= 0L) return budget.stop(LpProbeDecline.ALLOWANCE)
        val entries = offered / 4L
        // Keep capacity for retiring factors after a stopped or cancelled solve.
        val solveGrant = offered - entries
        if (solveGrant <= 0L) return budget.stop(LpProbeDecline.ALLOWANCE)
        if (token()) return budget.stop(LpProbeDecline.CANCELLED)
        val solver = factory(child, solveGrant, entries)
        var recorded = false
        solver.use {
            try {
                val result = solver.solve(warm)
                if (result == null || !result.optimal || token()) {
                    return budget.stop(when {
                        token() -> LpProbeDecline.CANCELLED
                        solver.lastTermination == LpFloatTermination.WORK -> LpProbeDecline.ALLOWANCE
                        else -> LpProbeDecline.CHILD
                    })
                }
                budget.record(solver, scaling, entries)
                recorded = true
                val scan = 2L * child.numVars + child.m + 1L
                if (scan > budget.remaining()) return budget.stop(LpProbeDecline.ALLOWANCE)
                budget.prepare(scan)
                val objective = solver.probeObjective(token) ?: return null
                val gain = objective - parentObjective
                if (budget.overspent()) return budget.stop(LpProbeDecline.ALLOWANCE)
                if (!gain.isFinite() || gain < 0.0 || token()) return null
                budget.completed++
                return gain
            } finally {
                if (!recorded) budget.record(solver, scaling, entries)
            }
        }
    }

    private class ProbeBudget(private val allowance: Long) {
        private var preparation = 0L
        private var solving = 0L
        var completed = 0
        var decline: LpProbeDecline? = null

        fun remaining(): Long = if (preparation >= allowance || solving >= allowance - preparation) 0L
            else allowance - preparation - solving

        fun overspent(): Boolean = preparation > allowance || solving > allowance - preparation

        fun prepare(work: Long) { preparation += minOf(work, Long.MAX_VALUE - preparation) }

        fun record(solver: RevisedSimplex, scaling: Long, entries: Long) {
            val metrics = solver.lastMetrics
            val construction = maxOf(metrics.preparationOps, solver.scalingMetrics.work)
            if (construction <= scaling) preparation -= scaling - construction
            else prepare(construction - scaling)
            prepare(if (metrics.basisBuildUnmeasured > 0L) entries else minOf(entries, metrics.factorEntriesBuilt))
            solving += minOf(metrics.workOps, Long.MAX_VALUE - solving)
        }

        fun stop(reason: LpProbeDecline): Double? {
            decline = reason
            return null
        }

        fun metrics(): LpProbeMetrics = LpProbeMetrics(preparation, solving, completed, decline)
    }
}
