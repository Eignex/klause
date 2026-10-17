package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationStatus
import com.eignex.klause.simplex.exact.ExactContinuation
import com.eignex.klause.simplex.exact.ExactContinuationInput
import com.eignex.klause.simplex.exact.ExactContinuationLimits

internal sealed interface PolynomialLinearResult {
    class Satisfiable(val point: Map<Int, BigFraction>) : PolynomialLinearResult
    data object Unsatisfiable : PolynomialLinearResult
}

internal fun decidePolynomialLinearRegion(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
    work: QfNraWork,
): PolynomialLinearResult? {
    val rows = ArrayList<PolynomialLinearRow>()
    val variables = HashSet<Int>()
    val limits = ExactContinuationLimits()
    for (atom in atoms) {
        arithmetic.step()
        var constant = BigFraction.ZERO
        val coefficients = LinkedHashMap<Int, BigFraction>()
        for (term in atom.polynomial.terms) {
            arithmetic.step()
            if (term.monomial == Monomial.ONE) {
                constant = term.coefficient
                continue
            }
            val variable = term.monomial.variables.singleOrNull() ?: return null
            if (term.monomial.exponent(variable) != 1) return null
            coefficients[variable] = term.coefficient
        }
        if (coefficients.isEmpty()) {
            if (!atom.holds(constant.signum())) return PolynomialLinearResult.Unsatisfiable
            continue
        }
        if (atom.relation == PolynomialRelation.NE) return null
        if (rows.size == limits.maxRows) return null
        val increasing = atom.relation == PolynomialRelation.GE || atom.relation == PolynomialRelation.GT
        val oriented = coefficients.mapValues { (_, value) ->
            arithmetic.step()
            if (increasing) value.negated() else value
        }
        variables.addAll(oriented.keys)
        rows.add(PolynomialLinearRow(oriented, if (increasing) constant else constant.negated(),
            atom.relation == PolynomialRelation.GT || atom.relation == PolynomialRelation.LT,
            atom.relation == PolynomialRelation.EQ))
    }
    if (rows.isEmpty()) return PolynomialLinearResult.Satisfiable(emptyMap())
    if (variables.size.toLong() + rows.size > limits.maxColumns) return null
    val ids = variables.sortedWith { left, right -> arithmetic.step(); left.compareTo(right) }
    return solveLinearRows(rows, ids, arithmetic, work, limits)
}

private fun solveLinearRows(
    rows: List<PolynomialLinearRow>,
    ids: List<Int>,
    arithmetic: PolynomialArithmetic,
    work: QfNraWork,
    limits: ExactContinuationLimits,
): PolynomialLinearResult? {
    val n = ids.size
    val m = rows.size
    if (n.toLong() + m > limits.maxColumns || m.toLong() * (n.toLong() + 2L * m + 1L) > limits.maxCells) {
        return null
    }
    val columns = List(n) { column ->
        arithmetic.step()
        rows.mapIndexedNotNull { row, value ->
            arithmetic.step()
            value.coefficients[ids[column]]?.let { row to it }
        }
    }
    val input = ExactContinuationInput(
        columns,
        rows.map { arithmetic.step(); it.rhs },
        List(n + m) { arithmetic.step(); if (it < n) null else BigFraction.ZERO },
        List(n + m) { arithmetic.step(); if (it >= n && rows[it - n].equality) BigFraction.ZERO else null },
        List(m) { arithmetic.step(); n + it },
        List(n + m) { arithmetic.step(); if (it < n) ContinuationStatus.FREE else ContinuationStatus.BASIC },
        lowerStrict = List(n + m) { arithmetic.step(); it >= n && rows[it - n].strict },
    )
    arithmetic.step()
    val result = ExactContinuation(input).resume(limits, cancellation = work, nativeWork = work)
    if (result.metrics.decline == ContinuationDecline.CANCELLED) throw PolynomialInterrupted()
    arithmetic.step()
    result.values?.let { return checkedLinearPoint(rows, ids, it, arithmetic) }
    val ray = result.ray ?: return null
    return if (linearConflict(rows, columns, ray, arithmetic)) PolynomialLinearResult.Unsatisfiable else null
}

private fun checkedLinearPoint(
    rows: List<PolynomialLinearRow>,
    ids: List<Int>,
    values: List<BigFraction>,
    arithmetic: PolynomialArithmetic,
): PolynomialLinearResult.Satisfiable? {
    if (values.size != ids.size + rows.size) return null
    val point = ids.mapIndexed { index, id -> arithmetic.step(); id to values[index] }.toMap()
    for (row in rows) {
        var activity = BigFraction.ZERO
        for ((id, coefficient) in row.coefficients) {
            arithmetic.step()
            activity += coefficient * point.getValue(id)
        }
        arithmetic.step()
        val comparison = activity.compareTo(row.rhs)
        val holds = if (row.equality) comparison == 0 else if (row.strict) comparison < 0 else comparison <= 0
        if (!holds) return null
    }
    arithmetic.step()
    return PolynomialLinearResult.Satisfiable(point)
}

private fun linearConflict(
    rows: List<PolynomialLinearRow>,
    columns: List<List<Pair<Int, BigFraction>>>,
    ray: List<BigFraction>,
    arithmetic: PolynomialArithmetic,
): Boolean {
    if (ray.size != rows.size) return false
    var bound = BigFraction.ZERO
    var strict = false
    for ((index, row) in rows.withIndex()) {
        arithmetic.step()
        val multiplier = ray[index]
        if (!row.equality && multiplier.signum() > 0) return false
        if (row.strict && multiplier.signum() < 0) strict = true
        bound += multiplier * row.rhs
    }
    for (column in columns) {
        var coefficient = BigFraction.ZERO
        for ((row, value) in column) {
            arithmetic.step()
            coefficient += ray[row] * value
        }
        if (!coefficient.isZero) return false
    }
    arithmetic.step()
    // The combined left side is at most zero, and strictly below it if a strict lower side participates.
    return bound.signum() > 0 || (bound.isZero && strict)
}

private class PolynomialLinearRow(
    val coefficients: Map<Int, BigFraction>,
    val rhs: BigFraction,
    val strict: Boolean,
    val equality: Boolean,
)
