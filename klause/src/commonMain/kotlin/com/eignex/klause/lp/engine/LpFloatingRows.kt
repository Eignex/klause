package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.nextDown
import kotlin.math.nextUp

internal class LpNumericalRowBound(
    private val model: LpModel,
    val column: Int,
    val upper: Boolean,
    val shiftedValue: Double,
) {
    fun belongsTo(candidate: LpModel): Boolean = candidate === model
}

internal class LpFloatingRows(
    val bounds: List<LpNumericalRowBound>,
    val work: Long,
    val stop: LpFloatingBoundStop?,
)

internal fun floatingRowBounds(
    model: LpModel,
    workLimit: Long,
    allocationLimit: Long,
    cancellation: Cancellation = Cancellation.Never,
): LpFloatingRows {
    val meter = FloatingRowWork(workLimit, allocationLimit, cancellation)
    val completed = ArrayList<LpNumericalRowBound>()
    try {
        val system = FloatingRowSystem(model, meter, completed)
        system.project()
    } catch (_: FloatingRowStop) {
        // Completed projections have inspected their full row combination and endpoint enclosure.
    }
    val cancelled = cancellation()
    return LpFloatingRows(if (cancelled) emptyList() else completed, meter.work,
        if (cancelled) LpFloatingBoundStop.CANCELLED else meter.stop)
}

private class FloatingRowTerm(val column: Int, val coefficient: FloatingRowInterval)
private class FloatingLpRow(val terms: List<FloatingRowTerm>, val rhs: FloatingRowInterval)

private class FloatingRowSystem(
    private val model: LpModel,
    private val meter: FloatingRowWork,
    private val completed: MutableList<LpNumericalRowBound>,
) {
    private val arithmetic = FloatingRowArithmetic(meter)
    private val rows: Array<FloatingLpRow>
    private val continuousCounts: IntArray

    init {
        meter.visit()
        if (model.numVars > 4096) meter.refuse(LpFloatingBoundStop.INPUT)
        val entries = (model.doubleView?.colVal?.size ?: model.csc.colVal.size).toLong() + model.m
        meter.visit(model.m.toLong() + model.n, 256L + 128L * model.m + 96L * entries)
        val terms = Array(model.m) { ArrayList<FloatingRowTerm>() }
        continuousCounts = IntArray(model.m)
        for (column in 0 until model.n) {
            meter.visit()
            model.forEachInColumnD(column) { row, coefficient ->
                meter.visit()
                if (!coefficient.isFinite()) meter.refuse(LpFloatingBoundStop.NON_FINITE)
                if (coefficient != 0.0) {
                    terms[row].add(FloatingRowTerm(column, FloatingRowInterval(coefficient, coefficient)))
                    if (!integral(column)) continuousCounts[row]++
                }
            }
        }
        rows = Array(model.m) { row ->
            meter.visit()
            val rhs = model.rhsD(row)
            if (!rhs.isFinite()) meter.refuse(LpFloatingBoundStop.NON_FINITE)
            terms[row].add(FloatingRowTerm(model.slackCol(row), FloatingRowInterval(1.0, 1.0)))
            FloatingLpRow(terms[row], FloatingRowInterval(rhs, rhs))
        }
    }

    private fun integral(column: Int): Boolean = column < model.n &&
        (model.exactState?.model?.column(column)?.integral ?: !model.colContinuous[column])

    private fun active(row: Int): Boolean = model.exactState?.rows?.row(row)?.active != false

    fun project() {
        for (row in rows.indices) {
            meter.visit()
            if (continuousCounts[row] > 0 && active(row)) project(rows[row])
        }
        projectPairs()
        projectDefinitions()
    }

    private fun project(row: FloatingLpRow) {
        project(row, false)
        project(row, true)
    }

    private fun endpoint(term: FloatingRowTerm, negate: Boolean): Double? {
        meter.visit()
        val coefficient = if (negate) term.coefficient.negated() else term.coefficient
        if (coefficient.zero) return 0.0
        val column = term.column
        val lower = if (model.hasFiniteLower(column) && !model.probeClampedLo.getOrElse(column) { false }) {
            model.lowerD(column)
        } else Double.NEGATIVE_INFINITY
        val upper = if (model.hasFiniteUpper(column) && !model.probeClampedHi.getOrElse(column) { false }) {
            model.upperD(column)
        } else Double.POSITIVE_INFINITY
        if (lower.isNaN() || upper.isNaN() || lower > upper) meter.refuse(LpFloatingBoundStop.INPUT)
        if ((!lower.isFinite() && coefficient.upper > 0.0) ||
            (!upper.isFinite() && coefficient.lower < 0.0)
        ) return null
        var value = Double.POSITIVE_INFINITY
        if (lower.isFinite()) value = minOf(value,
            arithmetic.productLower(coefficient.lower, lower), arithmetic.productLower(coefficient.upper, lower))
        if (upper.isFinite()) value = minOf(value,
            arithmetic.productLower(coefficient.lower, upper), arithmetic.productLower(coefficient.upper, upper))
        return value.takeIf { it.isFinite() }
    }

    private fun project(row: FloatingLpRow, negate: Boolean) {
        meter.visit(row.terms.size.toLong(), 128L + 96L * row.terms.size)
        val endpoints = arrayOfNulls<Double>(row.terms.size)
        var minimum = 0.0
        var missing = 0
        for ((index, term) in row.terms.withIndex()) {
            val value = endpoint(term, negate)
            endpoints[index] = value
            if (value == null) missing++ else minimum = arithmetic.sumLower(minimum, value)
        }
        if (!minimum.isFinite()) return
        for ((index, term) in row.terms.withIndex()) {
            meter.visit()
            if (!integral(term.column)) continue
            val own = endpoints[index]
            val otherMissing = missing - if (own == null) 1 else 0
            if (otherMissing != 0) continue
            val coefficient = if (negate) term.coefficient.negated() else term.coefficient
            val upper = coefficient.lower > 0.0
            if (!upper && coefficient.upper >= 0.0) continue
            val others = if (own == null) minimum else arithmetic.sumLower(minimum, -own)
            val rhs = if (negate) -row.rhs.lower else row.rhs.upper
            val residual = arithmetic.sumUpper(rhs, -others)
            meter.visit(2L)
            val a = residual / coefficient.lower
            val b = residual / coefficient.upper
            val value = if (upper) maxOf(a, b).nextUp() else minOf(a, b).nextDown()
            if (!value.isFinite()) continue
            val improves = if (upper) !model.hasFiniteUpper(term.column) || value < model.upperD(term.column)
                else !model.hasFiniteLower(term.column) || value > model.lowerD(term.column)
            if (!improves) continue
            if (completed.size == 128) meter.refuse(LpFloatingBoundStop.WORK)
            meter.visit(bytes = 64L)
            completed.add(LpNumericalRowBound(model, term.column, upper, value))
        }
    }

    private fun combine(first: FloatingLpRow, second: FloatingLpRow, multiplier: Double): FloatingLpRow {
        meter.visit(model.numVars.toLong(), 128L + 16L * model.numVars)
        val coefficients = arrayOfNulls<FloatingRowInterval>(model.numVars)
        for (term in first.terms) {
            meter.visit()
            coefficients[term.column] = term.coefficient
        }
        for (term in second.terms) {
            meter.visit()
            val scaled = arithmetic.scale(term.coefficient, multiplier)
            coefficients[term.column] = coefficients[term.column]?.let { arithmetic.add(it, scaled) } ?: scaled
        }
        meter.visit(model.numVars.toLong(), 128L + 32L * model.numVars)
        val terms = ArrayList<FloatingRowTerm>()
        for (column in coefficients.indices) {
            meter.visit()
            coefficients[column]?.takeUnless { it.zero }?.let { terms.add(FloatingRowTerm(column, it)) }
        }
        return FloatingLpRow(terms, arithmetic.add(first.rhs, arithmetic.scale(second.rhs, multiplier)))
    }

    private fun projectPairs() {
        for (column in 0 until model.n) {
            meter.visit()
            if (integral(column)) continue
            meter.visit(bytes = 64L + 32L * model.m)
            val candidates = ArrayList<Pair<Int, Double>>()
            model.forEachInColumnD(column) { row, coefficient ->
                meter.visit()
                if (coefficient != 0.0 && continuousCounts[row] == 1 && active(row)) candidates.add(row to coefficient)
            }
            var attempts = 0
            pairs@ for (first in 0 until candidates.lastIndex) {
                for (second in first + 1..candidates.lastIndex) {
                    meter.visit()
                    if (attempts++ == 8) break@pairs
                    val a = candidates[first]
                    val b = candidates[second]
                    val multiplier = -a.second / b.second
                    if (multiplier.isFinite()) project(combine(rows[a.first], rows[b.first], multiplier))
                }
            }
        }
    }

    private fun projectDefinitions() {
        meter.visit(model.n.toLong(), 4L * model.n)
        val definitions = IntArray(model.n) { -1 }
        for (row in rows.indices) {
            meter.visit()
            if (continuousCounts[row] != 1 || !active(row) || !model.fixed(model.slackCol(row)) ||
                model.rowStrict[row]
            ) continue
            for (term in rows[row].terms) {
                meter.visit()
                if (term.column < model.n && !integral(term.column) && definitions[term.column] < 0) {
                    definitions[term.column] = row
                }
            }
        }
        var attempts = 0
        for (row in rows.indices) {
            meter.visit()
            if (continuousCounts[row] < 2 || !active(row)) continue
            if (attempts++ == 8) break
            var combined = rows[row]
            var count = 0
            for (term in rows[row].terms) {
                meter.visit()
                if (term.column >= model.n || integral(term.column)) continue
                val definition = definitions[term.column]
                if (definition < 0 || count++ == 128) break
                val pivot = rows[definition].terms.first {
                    meter.visit()
                    it.column == term.column
                }.coefficient.lower
                val multiplier = -term.coefficient.lower / pivot
                if (!multiplier.isFinite()) break
                combined = combine(combined, rows[definition], multiplier)
            }
            if (count > 0) project(combined)
        }
    }
}
