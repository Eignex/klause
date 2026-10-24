package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpInput
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation

internal class LinearAuthorityModel private constructor(
    private val original: List<ExactRationalInequality>,
    val rowIds: List<Int>,
    private val lower: List<Side?>,
    private val upper: List<Side?>,
    inactive: Set<Int>,
    val coordinateConflictWeights: List<BigFraction>? = null,
) {
    private class Side(val row: Int, val value: BigFraction)

    val rows: List<ExactRationalInequality> = rowIds.map { original[it] }
    val inactiveRows: Set<Int> = rowIds.indices.filterTo(HashSet()) { rowIds[it] in inactive }
    val columns: List<ExactLpColumn> = lower.indices.map { id ->
        ExactLpColumn(ExactLpBounds(
            lower[id]?.let { ExactLpSide(ExactLpNumber.of(it.value)) },
            upper[id]?.let { ExactLpSide(ExactLpNumber.of(it.value)) },
        ), integral = false)
    }

    fun input(costs: List<BigFraction>, constant: BigFraction): SourceLpInput {
        if (rowIds.size == original.size) return SourceLpInput(rows, costs.size, costs + constant)
        val scalars = ArrayList<BigFraction>()
        scalars += costs
        scalars += constant
        for (side in lower + upper) {
            if (side == null) continue
            scalars += side.value
            val row = original[side.row]
            val coefficient = row.coefficients.single()
            if (coefficient != BigFraction.ONE && coefficient != BigFraction.MINUS_ONE) {
                scalars += coefficient
                scalars += row.rhs
            }
        }
        return SourceLpInput(rows, costs.size, scalars.filter { !it.isZero })
    }

    // Column sides restore the residual coefficients in the original cited inequality sequence.
    fun lift(
        multipliers: List<BigFraction>,
        target: List<BigFraction>,
        cancellation: Cancellation,
    ): List<BigFraction>? {
        if (multipliers.size != rows.size || target.size != columns.size ||
            !multipliers.admittedSourcePoint() || !target.admittedSourcePoint()) return null
        val weights = MutableList(original.size) { BigFraction.ZERO }
        val residual = target.toMutableList()
        for (id in rows.indices) {
            if (cancellation()) return null
            val weight = multipliers[id]
            if (weight < BigFraction.ZERO) return null
            weights[rowIds[id]] += weight
            if (weight.isZero) continue
            val row = rows[id]
            for (entry in row.columns.indices) {
                if (cancellation()) return null
                val column = row.columns[entry]
                residual[column] -= weight * row.coefficients[entry]
                if (!listOf(residual[column]).admittedSourcePoint()) return null
            }
        }
        for (column in residual.indices) {
            if (cancellation()) return null
            val coefficient = residual[column]
            if (coefficient.isZero) continue
            val side = (if (coefficient < BigFraction.ZERO) lower[column] else upper[column]) ?: return null
            weights[side.row] += coefficient * original[side.row].coefficients.single().reciprocal()
        }
        return weights.takeIf { it.admittedSourcePoint() }
    }

    companion object {
        fun prepare(
            rows: List<ExactRationalInequality>,
            variables: Int,
            cancellation: Cancellation,
            inactive: Set<Int> = emptySet(),
        ): LinearAuthorityModel? {
            fun identity(conflict: List<BigFraction>? = null) = LinearAuthorityModel(
                rows, rows.indices.toList(), List(variables) { null }, List(variables) { null }, inactive, conflict)
            val lower = MutableList<Side?>(variables) { null }
            val upper = MutableList<Side?>(variables) { null }
            val retained = ArrayList<Int>()
            for (id in rows.indices) {
                if (cancellation()) return null
                val row = rows[id]
                if (row.strict || row.columns.size != 1 || row.coefficients.single().isZero) {
                    retained += id
                    continue
                }
                val column = row.columns.single()
                if (id in inactive) continue
                val coefficient = row.coefficients.single()
                if (column !in 0 until variables || !listOf(coefficient, row.rhs).admittedSourcePoint()) {
                    return identity()
                }
                val value = row.rhs * coefficient.reciprocal()
                if (!listOf(value).admittedSourcePoint()) return identity()
                val bounds = if (coefficient < BigFraction.ZERO) lower else upper
                val previous = bounds[column]
                if (previous == null || if (coefficient < BigFraction.ZERO) value > previous.value
                    else value < previous.value) bounds[column] = Side(id, value)
            }
            for (column in lower.indices) {
                if (cancellation()) return null
                val lo = lower[column] ?: continue
                val hi = upper[column] ?: continue
                if (lo.value <= hi.value) continue
                val weights = MutableList(rows.size) { BigFraction.ZERO }
                weights[lo.row] = rows[lo.row].coefficients.single().reciprocal().negated()
                weights[hi.row] = rows[hi.row].coefficients.single().reciprocal()
                return identity(weights.takeIf { it.admittedSourcePoint() })
            }
            return LinearAuthorityModel(rows, retained, lower, upper, inactive)
        }
    }
}
