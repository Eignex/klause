package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.util.Cancellation

internal data class LpDirectionalGain(val down: Double, val up: Double)

internal data class LpDirectionalValues(val down: Double, val up: Double)

internal data class LpProbeSource(val model: LpModel, val basis: Basis)

internal enum class LpProbeDecline {
    CANCELLED, ALLOWANCE, OWNER, BUSY, STALE_POINT, NO_OPTIMUM, NO_POINT, SPLIT, CHILD,
}

internal enum class LpProbeReuse { INDEPENDENT, IDENTITY, SCALED }

internal enum class LpProbePreparationStop { WORKSPACE, SCALARS, FACTORS }

internal data class LpProbePreflight(
    val workspaceWork: Long,
    val scalarWork: Long,
    val offeredWork: Long,
    val reuse: LpProbeReuse,
    val ownerReused: Boolean,
    val stop: LpProbePreparationStop?,
    val factorCopyWork: Long = 0L,
    val factorCopyEntries: Long = 0L,
    val sourceCopyWorkSaved: Long = 0L,
    val parentValueWork: Long = 0L,
)

internal data class LpProbeNumerical(
    val workAllowance: Long,
    val entryLimit: Long,
    val termination: LpFloatTermination?,
    val pivots: Int,
    val basisBuildWork: Long,
    val basisBuildDeclines: Long,
    val factorEntriesBuilt: Long,
    val orderHint: Boolean = false,
    val lastBuildOrderReused: Long = 0L,
    val lastBuildOrderFallbacks: Long = 0L,
    val factorCopy: Boolean = false,
    val copiedFactorEntries: Long = 0L,
    val parentValues: Boolean = false,
)

internal data class LpProbeMetrics(
    val preparationWork: Long,
    val solveWork: Long,
    val completed: Int,
    val decline: LpProbeDecline? = null,
    val preflight: LpProbePreflight? = null,
    val numerical: LpProbeNumerical? = null,
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
    private val sourceView: LpScalingView? = null,
    private val orderPreparation: Long = 0L,
    private val factorPreparation: Long = 0L,
    private val factorEntries: Long = 0L,
    private val factorEntryLimit: Long = Long.MAX_VALUE,
    private val sharedBasisSource: Boolean = false,
    private val parentValuePreparation: Long = 0L,
    private val factory: (LpModel, Long, Long, LpScalingView?) -> RevisedSimplex,
) {
    init {
        require(sourceView == null || sourceView.source.exactState === state)
        require(orderPreparation >= 0L)
        require(factorPreparation >= 0L && factorEntries >= 0L && factorEntryLimit >= 0L)
        require(!sharedBasisSource || (sourceView != null && factorPreparation > 0L))
        require(parentValuePreparation >= 0L && (parentValuePreparation == 0L || factorPreparation > 0L))
    }

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
        val budget = ProbeSession(allowance, 2 * columns.size)
        try {
            budget.use {
                val results = ArrayList<T>(columns.size)
                for (index in columns.indices) {
                    val down = childValue(columns[index], splits[index], true, budget, token, mapping.value)
                        ?: return null
                    budget.publish(observe)
                    val up = childValue(columns[index], splits[index] + 1L, false, budget, token, mapping.value)
                        ?: return null
                    budget.publish(observe)
                    results.add(mapping.pair(down, up))
                }
                if (token()) {
                    budget.decline = LpProbeDecline.CANCELLED
                    return null
                }
                return results
            }
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
            budget.publish(observe)
        }
    }

    private fun childValue(
        column: Int,
        endpoint: Long,
        upper: Boolean,
        budget: ProbeSession,
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
        val reusable = sourceView != null
        val existing = budget.owner
        val matrixEntries = (child.doubleView?.colPtr ?: child.csc.colPtr)[child.n].toLong() + child.m
        val sourceCopyWork = if (existing == null && reusable) 4L * matrixEntries else 0L
        val savedSourceWork = if (sharedBasisSource) sourceCopyWork else 0L
        // Retained private source snapshots eliminate both coefficient copies; scratch stays private and prepaid.
        val workspace = if (existing == null) {
            16L * child.numVars + 40L * child.m + 64L + orderPreparation + factorPreparation + parentValuePreparation +
                sourceCopyWork - savedSourceWork
        } else 0L
        val scaling = when {
            sourceView != null -> sourceView.refreshWorkBound(child)
            else -> LpScalingView.constructionWorkBound(child)
        }
        val preparationStop = when {
            workspace >= budget.remaining() -> LpProbePreparationStop.WORKSPACE
            scaling >= budget.remaining() - workspace -> LpProbePreparationStop.SCALARS
            existing == null && (factorEntries > factorEntryLimit ||
                factorEntries >= budget.remaining() - workspace - scaling) -> LpProbePreparationStop.FACTORS
            else -> null
        }
        val reuse = when {
            sourceView == null -> LpProbeReuse.INDEPENDENT
            sourceView.applied -> LpProbeReuse.SCALED
            else -> LpProbeReuse.IDENTITY
        }
        budget.preflight = LpProbePreflight(workspace, scaling, budget.remaining(), reuse,
            existing != null, preparationStop, if (existing == null) factorPreparation else 0L,
            if (existing == null) factorEntries else 0L, savedSourceWork,
            if (existing == null) parentValuePreparation else 0L)
        if (preparationStop != null) {
            budget.decline = LpProbeDecline.ALLOWANCE
            return null
        }
        budget.prepare(workspace)
        budget.prepare(scaling)
        val offered = budget.remaining()
        if (offered <= 0L) return budget.stop(LpProbeDecline.ALLOWANCE)
        val entries = if (existing == null) minOf(factorEntryLimit,
            maxOf(factorEntries, (offered + orderPreparation + factorPreparation) / budget.remainingChildren / 4L))
            else budget.entryLimit
        // Keep capacity for retiring factors after a stopped or cancelled solve.
        val solveGrant = offered - entries
        if (entries <= 0L || solveGrant <= 0L) return budget.stop(LpProbeDecline.ALLOWANCE)
        if (token()) return budget.stop(LpProbeDecline.CANCELLED)
        val solver = if (existing != null) {
            if (!existing.adoptProbe(child, requireNotNull(sourceView), token)) {
                return budget.stop(if (token()) LpProbeDecline.CANCELLED else LpProbeDecline.CHILD)
            }
            existing
        } else {
            val view = sourceView?.refresh(child)
            if (reusable && view == null) return budget.stop(LpProbeDecline.CHILD)
            factory(child, solveGrant, entries, view).also {
                if (reusable) {
                    budget.owner = it
                    budget.entryLimit = entries
                }
            }
        }
        solver.boundProbeWork(solveGrant)
        var recorded = false
        try {
            try {
                val result = if (existing == null) solver.solve(warm)
                    else solver.resolveBounds(LpFloatAllowance(solveGrant, 0))
                if (result == null || !result.optimal || token()) {
                    return budget.stop(when {
                        token() -> LpProbeDecline.CANCELLED
                        solver.lastTermination == LpFloatTermination.WORK -> LpProbeDecline.ALLOWANCE
                        else -> LpProbeDecline.CHILD
                    })
                }
                budget.record(solver, scaling, entries, solveGrant, existing == null)
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
                if (!recorded) budget.record(solver, scaling, entries, solveGrant, existing == null)
            }
        } finally {
            if (!reusable) solver.close()
        }
    }

    private class ProbeSession(private val allowance: Long, private val queries: Int) : AutoCloseable {
        private var preparation = 0L
        private var solving = 0L
        private var reportedPreparation = 0L
        private var reportedSolving = 0L
        private var reportedCompleted = 0
        var owner: RevisedSimplex? = null
        var entryLimit = 0L
        var completed = 0
        var decline: LpProbeDecline? = null
        var preflight: LpProbePreflight? = null
        var numerical: LpProbeNumerical? = null
        val remainingChildren: Int get() = queries - completed

        fun remaining(): Long = if (preparation >= allowance || solving >= allowance - preparation) 0L
            else allowance - preparation - solving

        fun overspent(): Boolean = preparation > allowance || solving > allowance - preparation

        fun prepare(work: Long) { preparation += minOf(work, Long.MAX_VALUE - preparation) }

        fun record(solver: RevisedSimplex, scaling: Long, entries: Long, grant: Long, initial: Boolean) {
            val metrics = solver.lastMetrics
            val build = if (metrics.basisBuildWork > 0L) solver.probeBasisBuild() else null
            numerical = LpProbeNumerical(grant, entries, solver.lastTermination, metrics.pivots,
                metrics.basisBuildWork, metrics.basisBuildDeclines, metrics.factorEntriesBuilt,
                initial && solver.hasInitialOrder, build?.reusedOrders ?: 0L, build?.fallbacks ?: 0L,
                initial && solver.hasImportedFactors, if (initial) solver.importedFactorEntries else 0L,
                initial && solver.hasImportedParentValues)
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

        fun publish(observe: (LpProbeMetrics) -> Unit) {
            val metrics = LpProbeMetrics(preparation - reportedPreparation, solving - reportedSolving,
                completed - reportedCompleted, decline, preflight, numerical)
            preflight = null
            numerical = null
            reportedPreparation = preparation
            reportedSolving = solving
            reportedCompleted = completed
            observe(metrics)
        }

        override fun close() {
            val held = owner
            owner = null
            held?.close()
        }
    }
}
