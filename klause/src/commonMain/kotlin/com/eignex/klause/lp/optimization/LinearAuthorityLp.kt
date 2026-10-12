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
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation

internal class LinearAuthorityLp(private val budget: SourceLpBudget) {
    fun solve(
        rows: List<ExactRationalInequality>,
        costs: List<BigFraction>,
        constant: BigFraction,
        cancellation: Cancellation,
    ): CertifiedLpResult? = budget.run(rows, costs.size, cancellation, rhs = costs + constant) { token ->
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            costs.indices.map { column -> rows.mapIndexedNotNull { index, row ->
                row.columns.indexOf(column).takeIf { it >= 0 }?.let {
                    ExactLpEntry(index, ExactLpNumber.of(row.coefficients[it]))
                }
            } },
            rows.map { ExactLpNumber.of(it.rhs) },
            List(costs.size) { ExactLpColumn(ExactLpBounds(), integral = false) } +
                rows.map { ExactLpColumn(ExactLpBounds(ExactLpSide(zero, it.strict)), integral = false) },
            rows.map { ExactLpRow(strict = it.strict) },
            ExactLpObjective(costs.map(ExactLpNumber::of) + List(rows.size) { zero }, ExactLpNumber.of(constant)),
        )
        val owner = LpScopedSolver(LpExactState(model), token, context = budget.context,
            iterationLimit = 1024, workLimit = 2_000_000L)
        try {
            val solved = owner.solve(token = token)
            solved?.takeIf { result -> result.exactPrimal?.admittedSourcePoint() != false &&
                result.lowerBound?.let { listOf(it).admittedSourcePoint() } != false }
        } finally {
            budget.observe(preparation = owner.metrics.preparationWork, floatWork = owner.lastMetrics.workOps,
                refinement = owner.refinementCache.work)
            budget.closeOwner(owner)
        }
    }

    fun combination(
        rows: List<ExactRationalInequality>,
        coefficients: List<BigFraction>,
        rhs: BigFraction,
        strict: Boolean,
        cancellation: Cancellation,
    ): List<BigFraction>? {
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
        val point = result.exactPrimal ?: return null
        if (strict && point[count] <= BigFraction.ZERO) return null
        return point.take(count)
    }
}
