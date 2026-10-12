package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.math.abs
import kotlin.math.max

internal class QuadraticCandidates(private val control: QuadraticControl = QuadraticControl()) {
    fun stationary(model: QuadraticModel, activeRows: List<Int>): QuadraticStationaryCertificate? {
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
        val solution = eliminate(matrix) ?: return null
        val multipliers = MutableList(rows.size) { BigFraction.ZERO }
        for ((j, row) in selected.withIndex()) { control.step(); multipliers[row] = solution[model.dimension + j] }
        control.poll()
        return QuadraticStationaryCertificate(solution.take(model.dimension), multipliers)
    }

    fun proposeActiveRows(model: QuadraticModel, start: List<Double>, iterations: Int = 32): List<Int>? {
        control.step()
        require(start.size == model.dimension && iterations >= 0)
        val hessian = model.hessian.map { row -> row.map { it.toDouble() } }
        val linear = model.linear.map { it.toDouble() }
        val rows = model.rows
        val coefficients = rows.map { row -> row.coefficients.map { it.toDouble() } }
        val rhs = rows.map { it.rhs.toDouble() }
        val numbers = hessian.flatten() + linear + coefficients.flatten() + rhs + start
        if (numbers.any { !it.isFinite() }) return null
        var norm = hessian.maxOfOrNull { row -> row.sumOf(::abs) } ?: 0.0
        for (row in coefficients) for (value in row) { control.step(); norm += 16.0 * value * value }
        if (!norm.isFinite()) return null
        val step = 1.0 / max(1.0, norm)
        val point = start.toDoubleArray()
        repeat(iterations) {
            val gradient = DoubleArray(point.size) { i ->
                var value = linear[i]
                for (j in point.indices) { control.step(); value += hessian[i][j] * point[j] }
                value
            }
            for (j in rows.indices) {
                var residual = -rhs[j]
                for (i in point.indices) { control.step(); residual += coefficients[j][i] * point[i] }
                if (rows[j].relation == QuadraticRowRelation.EQ || residual > 0.0) {
                    for (i in point.indices) { control.step(); gradient[i] += 16.0 * residual * coefficients[j][i] }
                }
            }
            for (i in point.indices) { control.step(); point[i] -= step * gradient[i] }
            if (point.any { !it.isFinite() }) return null
        }
        return rows.indices.filter { j ->
            var residual = -rhs[j]
            for (i in point.indices) { control.step(); residual += coefficients[j][i] * point[i] }
            rows[j].relation == QuadraticRowRelation.EQ || residual >= -1e-6
        }.also { control.poll() }
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
            for (j in column..size) { control.step(); matrix[row][j] = matrix[row][j] * scale }
            for (i in 0 until size) {
                if (i == row) continue
                val factor = matrix[i][column]
                for (j in column..size) { control.step(); matrix[i][j] = matrix[i][j] - factor * matrix[row][j] }
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
