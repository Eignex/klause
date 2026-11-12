package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.koblas.engine.BuiltinEngines
import com.eignex.koblas.engine.EngineImplementation
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
        val hessian = model.hessian.map { row -> DoubleArray(row.size) { row[it].toDouble() } }
        val linear = model.linear.let { values -> DoubleArray(values.size) { values[it].toDouble() } }
        val rows = model.rows
        val coefficients = rows.map { row ->
            row.coefficients.let { values -> DoubleArray(values.size) { values[it].toDouble() } }
        }
        val rhs = DoubleArray(rows.size) { rows[it].rhs.toDouble() }
        if (hessian.any { row -> row.any { !it.isFinite() } } || linear.any { !it.isFinite() } ||
            coefficients.any { row -> row.any { !it.isFinite() } } || rhs.any { !it.isFinite() } ||
            start.any { !it.isFinite() }
        ) return null
        var norm = hessian.maxOfOrNull { row -> row.sumOf(::abs) } ?: 0.0
        for (row in coefficients) for (value in row) { control.step(); norm += 16.0 * value * value }
        if (!norm.isFinite()) return null
        val step = 1.0 / max(1.0, norm)
        val point = start.toDoubleArray()
        val gradient = DoubleArray(point.size)
        repeat(iterations) {
            for (i in point.indices) gradient[i] = dot(linear[i], hessian[i], point)
            if (gradient.any { !it.isFinite() }) return null
            for (j in rows.indices) {
                val residual = dot(-rhs[j], coefficients[j], point)
                if (!residual.isFinite()) return null
                if (rows[j].relation == QuadraticRowRelation.EQ || residual > 0.0) {
                    val penalty = 16.0 * residual
                    if (!penalty.isFinite()) return null
                    axpy(penalty, coefficients[j], gradient)
                }
            }
            if (gradient.any { !it.isFinite() }) return null
            axpy(-step, gradient, point)
            if (point.any { !it.isFinite() }) return null
        }
        return rows.indices.filter { j ->
            val residual = dot(-rhs[j], coefficients[j], point)
            if (!residual.isFinite()) return null
            rows[j].relation == QuadraticRowRelation.EQ || residual >= -1e-6
        }.also { control.poll() }
    }

    private fun dot(initial: Double, left: DoubleArray, right: DoubleArray): Double {
        repeat(left.size) { control.step() }
        return initial + proposalEngine.dot(left, 0, right, 0, left.size)
    }

    private fun axpy(alpha: Double, source: DoubleArray, destination: DoubleArray) {
        repeat(source.size) { control.step() }
        proposalEngine.axpy(destination, 0, alpha, source, 0, source.size)
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

    private companion object {
        val proposalEngine by lazy {
            requireNotNull(BuiltinEngines.simd) { "Quadratic proposals require the JVM SIMD engine" }.also {
                check(it.id == "jvm-simd")
                check(EngineImplementation.Dot in it.implementations && EngineImplementation.Axpy in it.implementations)
            }
        }

    }
}
