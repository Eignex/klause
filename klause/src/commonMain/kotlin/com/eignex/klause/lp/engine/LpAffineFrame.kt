package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationBudget
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationStop

internal class LpAffineFrame private constructor(
    val source: LpExactState,
    val model: ExactLpModel,
    private val columns: List<Int>,
    private val rows: List<Int>,
    private val pivots: List<LpAffinePivot>,
) {
    fun liftPrimal(values: List<BigFraction>, budget: ContinuationBudget): List<BigFraction> {
        require(values.size == columns.size)
        val original = source.model
        budget.step(original.n.toLong(), original.n.toLong() * 32L)
        val centered = MutableList(original.n) { BigFraction.ZERO }
        for ((index, column) in columns.withIndex()) {
            centered[column] = subtract(values[index], original.column(column).origin.value, budget)
        }
        for (pivot in pivots.asReversed()) {
            var value = pivot.constant
            for ((column, coefficient) in pivot.terms) {
                value = subtract(value, multiply(coefficient, centered[column], budget), budget)
            }
            centered[pivot.column] = value
        }
        return centered.mapIndexed { column, value -> add(value, original.column(column).origin.value, budget) }
    }

    fun liftMultipliers(values: List<BigFraction>, ray: Boolean, budget: ContinuationBudget): List<BigFraction> {
        require(values.size == rows.size)
        budget.step(source.model.m.toLong(), source.model.m.toLong() * 32L)
        val lifted = MutableList(source.model.m) { BigFraction.ZERO }
        for ((index, row) in rows.withIndex()) lifted[row] = budget.fraction(values[index])
        for (pivot in pivots.asReversed()) {
            var value = if (ray) BigFraction.ZERO else pivot.cost
            for ((row, coefficient) in pivot.affected) {
                value = subtract(value, multiply(lifted[row], coefficient, budget), budget)
            }
            lifted[pivot.row] = multiply(value, pivot.coefficient, budget)
        }
        return lifted
    }

    companion object {
        fun capture(source: LpExactState, budget: ContinuationBudget): LpAffineFrame? {
            val model = source.model
            val limits = ReconstructionLimits()
            budget.step()
            if (model.numVars > limits.maxCoordinates) return null
            budget.step(model.numVars.toLong(), model.numVars.toLong() * 96L + model.m.toLong() * 128L)
            val matrix = Array(model.m) { linkedMapOf<Int, BigFraction>() }
            var entries = 0
            for (column in 0 until model.n) {
                budget.step()
                for (entry in model.columnEntries(column)) {
                    budget.step(bytes = 96L)
                    entries++
                    if (entries > limits.maxEntries) throw ContinuationStop(ContinuationDecline.DIMENSION)
                    val value = budget.fraction(entry.number.value)
                    if (!value.isZero) matrix[entry.row][column] = value
                }
            }
            val rhs = MutableList(model.m) { budget.fraction(model.rhs(it).value) }
            val costs = MutableList(model.numVars) { budget.fraction(model.objective.cost(it).value) }
            var constant = budget.fraction(model.objective.constant.value)
            val removedRows = BooleanArray(model.m)
            val removedColumns = BooleanArray(model.n)
            val pivots = ArrayList<LpAffinePivot>()
            for (row in 0 until model.m) {
                budget.step()
                val logical = model.column(model.n + row)
                val bounds = logical.bounds
                if (!source.rows.row(row).active || model.row(row).strict || !bounds.fixed) continue
                val fixed = budget.fraction(requireNotNull(bounds.lower).number.value)
                val candidate = matrix[row].entries.firstOrNull { (column, coefficient) ->
                    budget.step()
                    val sides = model.column(column).bounds
                    sides.lower == null && sides.upper == null &&
                        (coefficient == BigFraction.ONE || coefficient == BigFraction.MINUS_ONE)
                } ?: continue
                val column = candidate.key
                val coefficient = candidate.value
                val pivotConstant = multiply(subtract(rhs[row], fixed, budget), coefficient, budget)
                val terms = ArrayList<Pair<Int, BigFraction>>()
                for ((other, value) in matrix[row]) {
                    budget.step(bytes = 48L)
                    if (other != column) terms += other to multiply(value, coefficient, budget)
                }
                val affected = ArrayList<Pair<Int, BigFraction>>()
                for (otherRow in 0 until model.m) {
                    budget.step()
                    if (otherRow == row || removedRows[otherRow]) continue
                    val factor = matrix[otherRow].remove(column) ?: continue
                    budget.step(bytes = 48L)
                    affected += otherRow to factor
                    rhs[otherRow] = subtract(rhs[otherRow], multiply(factor, pivotConstant, budget), budget)
                    for ((otherColumn, value) in terms) {
                        val next = subtract(
                            matrix[otherRow][otherColumn] ?: BigFraction.ZERO,
                            multiply(factor, value, budget),
                            budget,
                        )
                        budget.step(bytes = 96L)
                        if (next.isZero) matrix[otherRow].remove(otherColumn) else matrix[otherRow][otherColumn] = next
                    }
                }
                val cost = costs[column]
                constant = add(constant, multiply(cost, pivotConstant, budget), budget)
                constant = add(constant, multiply(costs[model.n + row], fixed, budget), budget)
                for ((other, value) in terms) {
                    costs[other] = subtract(costs[other], multiply(cost, value, budget), budget)
                }
                budget.step(bytes = 128L)
                pivots += LpAffinePivot(row, column, coefficient, pivotConstant, terms, affected, cost)
                removedRows[row] = true
                removedColumns[column] = true
                matrix[row].clear()
            }
            if (pivots.isEmpty()) return null
            budget.step(model.n.toLong(), model.n.toLong() * 8L)
            val used = BooleanArray(model.n)
            for (row in matrix.indices) {
                budget.step()
                if (!removedRows[row]) {
                    for (column in matrix[row].keys) {
                        budget.step()
                        used[column] = true
                    }
                }
            }
            val columns = (0 until model.n).filter { column ->
                budget.step()
                val bounds = model.column(column).bounds
                !removedColumns[column] &&
                    (used[column] || bounds.lower != null || bounds.upper != null || !costs[column].isZero)
            }
            val rows = (0 until model.m).filter {
                budget.step()
                !removedRows[it]
            }
            budget.step(columns.size.toLong() + rows.size, (columns.size.toLong() + rows.size) * 128L)
            val rowIndices = IntArray(model.m) { -1 }
            for ((index, row) in rows.withIndex()) rowIndices[row] = index
            val entriesByColumn = Array(model.n) { ArrayList<ExactLpEntry>() }
            for (row in rows) {
                for ((column, value) in matrix[row]) {
                    budget.step(bytes = 64L)
                    entriesByColumn[column] += ExactLpEntry(rowIndices[row], ExactLpNumber.of(value))
                }
            }
            val layout = ExactLpModel(
                columns.map { entriesByColumn[it] },
                rows.map { ExactLpNumber.of(rhs[it]) },
                columns.map { model.column(it) } + rows.map { model.column(model.n + it) },
                rows.map { model.row(it) },
                ExactLpObjective(
                    columns.map { ExactLpNumber.of(costs[it]) } + rows.map { ExactLpNumber.of(costs[model.n + it]) },
                    ExactLpNumber.of(constant),
                    model.objective.scale,
                    model.objective.externalConstant,
                    model.objective.sense,
                ),
            )
            return LpAffineFrame(source, layout, columns, rows, pivots)
        }

        private fun add(a: BigFraction, b: BigFraction, budget: ContinuationBudget): BigFraction {
            budget.preflight(a, b)
            return budget.fraction(a + b)
        }

        private fun subtract(a: BigFraction, b: BigFraction, budget: ContinuationBudget): BigFraction {
            budget.preflight(a, b)
            return budget.fraction(a - b)
        }

        private fun multiply(a: BigFraction, b: BigFraction, budget: ContinuationBudget): BigFraction {
            budget.preflight(a, b)
            return budget.fraction(a * b)
        }
    }
}

private class LpAffinePivot(
    val row: Int,
    val column: Int,
    val coefficient: BigFraction,
    val constant: BigFraction,
    val terms: List<Pair<Int, BigFraction>>,
    val affected: List<Pair<Int, BigFraction>>,
    val cost: BigFraction,
)
