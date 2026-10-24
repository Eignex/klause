package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation

internal data class LinearAuthorityLpShape(val inputRows: Int, val rows: Int, val columns: Int, val admitted: Boolean)

internal data class LinearAuthorityLpResult(
    val result: CertifiedLpResult,
    val boundWeights: List<BigFraction>?,
    val conflictWeights: List<BigFraction>?,
)

internal class LinearAuthorityLp(
    private val budget: SourceLpBudget,
    private val onShape: (LinearAuthorityLpShape) -> Unit = {},
    retain: Boolean = false,
) : AutoCloseable {
    private val session = if (retain) LinearAuthoritySession(budget) else null
    val preparations: Long get() = session?.preparations ?: 0L
    val reuses: Long get() = session?.reuses ?: 0L
    private val observer = object : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
        override fun observeExactInput(accepted: Boolean) = Unit
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        override fun observeContinuation(metrics: ExactContinuationMetrics) {
            budget.observe(continuation = metrics.work)
        }
    }
    fun solve(
        rows: List<ExactRationalInequality>,
        costs: List<BigFraction>,
        constant: BigFraction,
        cancellation: Cancellation,
    ): LinearAuthorityLpResult? {
        var prepared: LinearAuthorityModel? = null
        return budget.runPrepared(cancellation, prepare = { token ->
            LinearAuthorityModel.prepare(rows, costs.size, token)?.also { prepared = it }
                ?.input(costs, constant)
        }) { token ->
            val packed = checkNotNull(prepared)
            val workingRows = packed.rows
            val zero = ExactLpNumber.of(0L)
            val objective = ExactLpObjective(
                costs.map(ExactLpNumber::of) + List(workingRows.size) { zero },
                ExactLpNumber.of(constant),
            )
            fun model() = ExactLpModel(
                costs.indices.map { column -> workingRows.mapIndexedNotNull { index, row ->
                    row.columns.indexOf(column).takeIf { it >= 0 }?.let {
                        ExactLpEntry(index, ExactLpNumber.of(row.coefficients[it]))
                    }
                } },
                workingRows.map { ExactLpNumber.of(it.rhs) },
                (if (session == null) packed.columns else packed.columns.map {
                    it.copy(bounds = ExactLpBounds())
                }) + workingRows.map {
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero, it.strict)), integral = false)
                },
                workingRows.map { ExactLpRow(strict = it.strict) },
                objective,
            )
            val owner = if (session != null) session.bind(packed, objective, token, ::model)
                ?: return@runPrepared null else LpScopedSolver(LpExactState(model()), token, context = budget.context,
                    iterationLimit = 1024, workLimit = 2_000_000L)
            val preparation = owner.metrics.preparationWork
            val refinement = owner.refinementCache.work
            try {
                val solved = owner.solve(token = token, observer = observer)
                val result = solved?.takeIf { result -> result.exactPrimal?.admittedSourcePoint() != false &&
                    result.lowerBound?.let { listOf(it).admittedSourcePoint() } != false } ?: return@runPrepared null
                val boundWeights = result.bound?.dualMultipliers?.map { it.negated() }
                    ?.let { packed.lift(it, costs.map { cost -> cost.negated() }, token) }
                val conflict = result.rationalConflict
                val conflictMultipliers = if (conflict != null) {
                    MutableList(workingRows.size) { BigFraction.ZERO }.also { weights ->
                        for (id in conflict.rows.indices) weights[conflict.rows[id]] += conflict.multipliers[id]
                    }
                } else result.farkasRay?.map { BigFraction.ofLong(it).negated() }
                val conflictWeights = conflictMultipliers?.let {
                    packed.lift(it, List(costs.size) { BigFraction.ZERO }, token)
                }
                LinearAuthorityLpResult(result, boundWeights, conflictWeights)
            } finally {
                budget.observe(preparation = owner.metrics.preparationWork - preparation,
                    floatWork = owner.lastMetrics.workOps, refinement = owner.refinementCache.work - refinement)
                if (session == null) budget.closeOwner(owner)
            }
        }.also {
            val workingRows = prepared?.rows?.size ?: rows.size
            onShape(LinearAuthorityLpShape(rows.size, workingRows, costs.size,
                budget.admitsDimensions(costs.size, workingRows.toLong())))
        }
    }

    override fun close() {
        session?.close()
    }

    fun combination(
        rows: List<ExactRationalInequality>,
        coefficients: List<BigFraction>,
        rhs: BigFraction,
        strict: Boolean,
        cancellation: Cancellation,
    ): List<BigFraction>? {
        if (session != null) return LinearAuthorityLp(budget, onShape).use {
            it.combination(rows, coefficients, rhs, strict, cancellation)
        }
        val count = rows.size
        val constraints = ArrayList<ExactRationalInequality>()
        fun equality(values: List<BigFraction>, bound: BigFraction) {
            val ids = values.indices.filter { !values[it].isZero }.toIntArray()
            constraints += ExactRationalInequality(ids, ids.map { values[it] }, bound)
            constraints += ExactRationalInequality(ids, ids.map { values[it].negated() }, bound.negated())
        }
        for (column in coefficients.indices) {
            equality(rows.map { row -> row.columns.indexOf(column).let {
                if (it < 0) BigFraction.ZERO else row.coefficients[it]
            } } + if (strict) listOf(BigFraction.ZERO) else emptyList(), coefficients[column])
        }
        equality(rows.map { it.rhs } + if (strict) listOf(BigFraction.ZERO) else emptyList(), rhs)
        val variables = count + if (strict) 1 else 0
        for (id in 0 until variables) constraints += ExactRationalInequality(intArrayOf(id),
            listOf(BigFraction.MINUS_ONE), BigFraction.ZERO)
        if (strict) {
            val selected = rows.indices.filter { rows[it].strict }
            if (selected.isEmpty()) return null
            constraints += ExactRationalInequality((selected + count).toIntArray(),
                List(selected.size) { BigFraction.MINUS_ONE } + BigFraction.ONE, BigFraction.ZERO)
            constraints += ExactRationalInequality(intArrayOf(count), listOf(BigFraction.ONE), BigFraction.ONE)
        }
        val result = solve(constraints, List(variables) {
            if (strict && it == count) BigFraction.MINUS_ONE else BigFraction.ZERO
        }, BigFraction.ZERO, cancellation) ?: return null
        val point = result.result.exactPrimal ?: return null
        if (strict && point[count] <= BigFraction.ZERO) return null
        return point.take(count)
    }
}
