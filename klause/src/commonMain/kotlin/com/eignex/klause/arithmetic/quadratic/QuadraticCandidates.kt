package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.koblas.koblas
import kotlin.math.max

internal class QuadraticCandidates(private val control: QuadraticControl = QuadraticControl()) {
    fun stationary(model: QuadraticModel, activeRows: List<Int>): QuadraticStationaryCertificate? = control.operation {
        control.step()
        val rows = model.rows
        require(activeRows.distinct().size == activeRows.size && activeRows.all { it in rows.indices })
        val selected = rows.indices.filter { rows[it].relation == QuadraticRowRelation.EQ || it in activeRows }
        require(selected.size <= Int.MAX_VALUE - model.dimension - 1)
        val size = model.dimension + selected.size
        control.matrix(size + 1)
        val hessian = model.hessian
        val linear = model.linear
        val coefficients = rows.map { it.coefficients }
        val matrix = Array(size) { Array(size + 1) { BigFraction.ZERO } }
        for (i in 0 until model.dimension) {
            for (j in 0 until model.dimension) { control.step(); matrix[i][j] = hessian[i][j] }
            for ((j, row) in selected.withIndex()) {
                control.step()
                matrix[i][model.dimension + j] = coefficients[row][i]
                matrix[model.dimension + j][i] = coefficients[row][i]
            }
            matrix[i][size] = linear[i].negated()
        }
        for ((j, row) in selected.withIndex()) { control.step(); matrix[model.dimension + j][size] = rows[row].rhs }
        val solution = eliminate(matrix) ?: return@operation null
        val multipliers = MutableList(rows.size) { BigFraction.ZERO }
        for ((j, row) in selected.withIndex()) { control.step(); multipliers[row] = solution[model.dimension + j] }
        control.poll()
        return@operation QuadraticStationaryCertificate(solution.take(model.dimension), multipliers)
    }

    fun proposeActiveRows(
        model: QuadraticModel, start: List<Double>, iterations: Int = 32,
    ): List<Int>? = control.operation(64) {
        control.step()
        require(start.size == model.dimension && iterations >= 0)
        val numeric = prepare(model, start) ?: return@operation null
        control.execution.boundary()
        val point = start.toDoubleArray()
        if (!improve(numeric, point, iterations)) return@operation null
        return@operation activeRows(numeric, point)
    }

    private fun prepare(model: QuadraticModel, start: List<Double>): NumericalProposal? {
        control.execution.boundary()
        val hessian = model.hessian.map { row ->
            control.execution.boundary()
            DoubleArray(row.size) { row[it].toDouble() }
        }
        val linear = model.linear.let { values -> DoubleArray(values.size) { values[it].toDouble() } }
        val rows = model.rows
        val coefficients = rows.map { row ->
            control.execution.boundary()
            row.coefficients.let { values -> DoubleArray(values.size) { values[it].toDouble() } }
        }
        val rhs = DoubleArray(rows.size) { rows[it].rhs.toDouble() }
        if (hessian.any { row -> row.any { !it.isFinite() } } || linear.any { !it.isFinite() } ||
            coefficients.any { row -> row.any { !it.isFinite() } } || rhs.any { !it.isFinite() } ||
            start.any { !it.isFinite() }
        ) return null
        var norm = hessian.maxOfOrNull { row ->
            control.execution.boundary()
            koblas.asum(row, 0, row.size)
        } ?: 0.0
        for (row in coefficients) norm += 16.0 * dot(0.0, row, row)
        if (!norm.isFinite()) return null
        val step = 1.0 / max(1.0, norm)
        return NumericalProposal(hessian, linear, rows, coefficients, rhs, step)
    }

    private fun improve(numeric: NumericalProposal, point: DoubleArray, iterations: Int): Boolean {
        control.execution.boundary()
        val gradient = DoubleArray(point.size)
        repeat(iterations) {
            for (i in point.indices) gradient[i] = dot(numeric.linear[i], numeric.hessian[i], point)
            if (gradient.any { !it.isFinite() } || !penalize(numeric, point, gradient)) return false
            if (gradient.any { !it.isFinite() }) return false
            axpy(-numeric.step, gradient, point)
            if (point.any { !it.isFinite() }) return false
        }
        return true
    }

    private fun penalize(numeric: NumericalProposal, point: DoubleArray, gradient: DoubleArray): Boolean {
        for (j in numeric.rows.indices) {
            val residual = dot(-numeric.rhs[j], numeric.coefficients[j], point)
            if (!residual.isFinite()) return false
            if (numeric.rows[j].relation == QuadraticRowRelation.EQ || residual > 0.0) {
                val penalty = 16.0 * residual
                if (!penalty.isFinite()) return false
                axpy(penalty, numeric.coefficients[j], gradient)
            }
        }
        return true
    }

    private fun activeRows(numeric: NumericalProposal, point: DoubleArray): List<Int>? {
        val active = mutableListOf<Int>()
        for (j in numeric.rows.indices) {
            val residual = dot(-numeric.rhs[j], numeric.coefficients[j], point)
            if (!residual.isFinite()) return null
            if (numeric.rows[j].relation == QuadraticRowRelation.EQ || residual >= -1e-6) active += j
        }
        control.poll()
        return active
    }

    private class NumericalProposal(
        val hessian: List<DoubleArray>,
        val linear: DoubleArray,
        val rows: List<QuadraticRow>,
        val coefficients: List<DoubleArray>,
        val rhs: DoubleArray,
        val step: Double,
    )

    private fun dot(initial: Double, left: DoubleArray, right: DoubleArray): Double {
        if (left.isEmpty()) return initial
        repeat(left.size) { control.step() }
        return initial + koblas.dot(left, 0, right, 0, left.size)
    }

    private fun axpy(alpha: Double, source: DoubleArray, destination: DoubleArray) {
        if (source.isEmpty()) return
        repeat(source.size) { control.step() }
        koblas.axpy(destination, 0, alpha, source, 0, source.size)
    }

    private fun eliminate(matrix: Array<Array<BigFraction>>): List<BigFraction>? {
        val size = matrix.size
        var row = 0
        val pivots = mutableListOf<Int>()
        for (column in 0 until size) {
            control.step()
            val pivot = (row until size).firstOrNull { control.step(); !matrix[it][column].isZero } ?: continue
            val swap = matrix[row]
            matrix[row] = matrix[pivot]
            matrix[pivot] = swap
            val scale = matrix[row][column].reciprocal()
            for (j in column..size) {
                control.exactStep(matrix[row][j], scale)
                matrix[row][j] = matrix[row][j] * scale
            }
            for (i in 0 until size) {
                if (i == row) continue
                val factor = matrix[i][column]
                for (j in column..size) {
                    control.exactStep(matrix[i][j], factor, matrix[row][j])
                    matrix[i][j] = matrix[i][j] - factor * matrix[row][j]
                }
            }
            pivots += column
            row++
        }
        for (i in row until size) { control.step(); if (!matrix[i][size].isZero) return null }
        val solution = MutableList(size) { BigFraction.ZERO }
        for ((i, column) in pivots.withIndex()) { control.step(); solution[column] = matrix[i][size] }
        return solution
    }
}
