package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.util.Cancellation

internal data class LpDirectionalGain(val down: Double, val up: Double)

internal data class LpDirectionalValues(val down: Double, val up: Double)

internal data class LpProbeSource(val model: LpModel, val basis: Basis)

internal enum class LpProbeDecline {
    CANCELLED, ALLOWANCE, OWNER, BUSY, STALE_POINT, NO_OPTIMUM, NO_POINT, SPLIT, CHILD,
}

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

internal interface LpDirectionalValueSolver {
    fun directionalValues(
        source: LpProbeSource,
        columns: IntArray,
        splits: LongArray,
        allowance: Long,
        token: Cancellation,
        observe: (LpProbeMetrics) -> Unit,
    ): List<LpDirectionalValues>?
}

internal class LpDirectionalProbes(
    private val state: LpExactState,
    private val warm: Basis,
    private val parentObjective: Double? = null,
    private val factory: (LpModel, Long, Long) -> RevisedSimplex,
) {
    fun run(
        columns: IntArray,
        splits: LongArray,
        allowance: Long,
        token: Cancellation,
        observe: (LpProbeMetrics) -> Unit,
    ): List<LpDirectionalGain>? {
        val reference = parentObjective ?: run {
            observe(LpProbeMetrics(0L, 0L, 0, LpProbeDecline.NO_OPTIMUM))
            return null
        }
        return query(columns, splits, allowance, token, observe, ResultMapping(
            value = { objective -> (objective - reference).takeIf { it.isFinite() && it >= 0.0 } },
            pair = ::LpDirectionalGain))
    }

    fun runValues(
        columns: IntArray,
        splits: LongArray,
        allowance: Long,
        token: Cancellation,
        observe: (LpProbeMetrics) -> Unit,
    ): List<LpDirectionalValues>? = query(columns, splits, allowance, token, observe,
        ResultMapping(value = { objective -> objective.takeIf { it.isFinite() } }, pair = ::LpDirectionalValues))

    private class ResultMapping<T>(val value: (Double) -> Double?, val pair: (Double, Double) -> T)

    private fun <T> query(
        columns: IntArray,
        splits: LongArray,
        allowance: Long,
        token: Cancellation,
        observe: (LpProbeMetrics) -> Unit,
        mapping: ResultMapping<T>,
    ): List<T>? {
        val decline = when {
            token() -> LpProbeDecline.CANCELLED
            allowance <= 0L -> LpProbeDecline.ALLOWANCE
            columns.isEmpty() || columns.size > 2 || columns.size != splits.size ||
                columns.any { it !in 0 until state.model.n } || splits.any { it == Long.MAX_VALUE } ||
                (columns.size == 2 && columns[0] == columns[1]) -> LpProbeDecline.SPLIT
            else -> null
        }
        if (decline != null) {
            observe(LpProbeMetrics(0L, 0L, 0, decline))
            return null
        }
        val budget = ProbeBudget(allowance, 2 * columns.size)
        try {
            val results = ArrayList<T>(columns.size)
            for (index in columns.indices) {
                val down = childValue(columns[index], splits[index], true, budget, token, mapping.value)
                    ?: return null
                val up = childValue(columns[index], splits[index] + 1L, false, budget, token, mapping.value)
                    ?: return null
                results.add(mapping.pair(down, up))
            }
            if (token()) {
                budget.decline = LpProbeDecline.CANCELLED
                return null
            }
            return results
        } catch (_: LpProjectionStop) {
            budget.decline = if (token()) LpProbeDecline.CANCELLED else LpProbeDecline.ALLOWANCE
            return null
        } catch (_: BasisArithmeticException) {
            budget.decline = LpProbeDecline.CHILD
            return null
        } catch (_: ArithmeticException) {
            budget.decline = LpProbeDecline.CHILD
            return null
        } finally {
            observe(budget.metrics())
        }
    }

    private fun childValue(
        column: Int,
        endpoint: Long,
        upper: Boolean,
        budget: ProbeBudget,
        token: Cancellation,
        value: (Double) -> Double?,
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
        child ?: return budget.stop(LpProbeDecline.CHILD)
        val workspace = 16L * child.numVars + 40L * child.m + 64L
        val scaling = LpScalingView.constructionWorkBound(child)
        if (workspace >= budget.remaining() || scaling >= budget.remaining() - workspace) {
            budget.decline = LpProbeDecline.ALLOWANCE
            return null
        }
        budget.prepare(workspace)
        budget.prepare(scaling)
        val offered = budget.remaining() / budget.remainingChildren
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
                val objective = solver.probeObjective(token) ?: return budget.stop(
                    if (token()) LpProbeDecline.CANCELLED else LpProbeDecline.CHILD,
                )
                if (budget.overspent()) return budget.stop(LpProbeDecline.ALLOWANCE)
                if (token()) return budget.stop(LpProbeDecline.CANCELLED)
                val estimate = value(objective) ?: return budget.stop(LpProbeDecline.CHILD)
                budget.completed++
                return estimate
            } finally {
                if (!recorded) budget.record(solver, scaling, entries)
            }
        }
    }

    private class ProbeBudget(private val allowance: Long, private val queries: Int) {
        private var preparation = 0L
        private var solving = 0L
        var completed = 0
        var decline: LpProbeDecline? = null
        val remainingChildren: Int get() = queries - completed

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
