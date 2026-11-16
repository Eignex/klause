package com.eignex.klause.solver.validation

import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

/**
 * Whether [direction], in declaration order, is a ray of the original source through the accepted [base] along
 * which the original objective strictly improves. Every coefficient is read exactly. A moved column has no bound
 * on the side it moves to, and every row active at [base] keeps its activity on its bounded sides, so no residual
 * of [base] grows while every moved term's magnitude eventually does: the ray's tail stays accepted under the
 * same tolerance. Integer columns, and with them the indicator guards, stay fixed, so integrality and the set of
 * active rows hold along the whole ray.
 */
internal fun checkLinearSourceRecession(
    base: LinearSourceWitness,
    direction: List<BigFraction>,
    stop: ExecutionControl,
): Boolean {
    val source = base.source
    val objective = source.objective ?: return false
    if (direction.size != source.variableValues.size || stop()) return false
    val poll = Poll(stop)
    for ((index, variable) in source.variableValues.withIndex()) {
        if (poll()) return false
        val sign = direction[index].signum()
        val blocking = if (sign < 0) variable.lower else variable.upper
        if (sign != 0 && (variable.integer || blocking != null)) return false
    }
    val values = base.values
    for (row in source.rowValues) {
        if (poll()) return false
        if (!row.activeAt(values)) continue
        val sign = slope(row.termValues, direction, poll)?.signum() ?: return false
        if ((row.lower != null && sign < 0) || (row.upper != null && sign > 0)) return false
    }
    val improvement = slope(objective.termValues, direction, poll)?.signum() ?: return false
    val improves = if (objective.sense == ObjectiveSense.MAXIMIZE) improvement > 0 else improvement < 0
    return improves && !stop()
}

/**
 * [direction] moved onto the exact kernel of the active source rows that its binary64 projection keeps level.
 * Those rows hold with a zero slope in the working model, but their decimal coefficients can leave the exact
 * slope a rounding error away from zero. Columns the ray does not move stay fixed, one column per independent
 * level row absorbs that error, and every other column keeps its step. Null when [stop] fires; the result proves
 * nothing until [checkLinearSourceRecession] accepts it.
 */
internal fun levelLinearSourceRay(
    base: LinearSourceWitness,
    direction: List<BigFraction>,
    stop: ExecutionControl,
): List<BigFraction>? {
    val source = base.source
    if (direction.size != source.variableValues.size || stop()) return null
    val poll = Poll(stop)
    val values = base.values
    val pivots = ArrayList<Pair<Int, HashMap<Int, BigFraction>>>()
    for (row in source.rowValues) {
        if (poll()) return null
        if (!row.activeAt(values)) continue
        val equation = row.levelEquation(direction, poll) ?: continue
        for ((column, pivot) in pivots) {
            if (poll()) return null
            equation.remove(column)?.let { factor -> equation.subtract(factor, pivot, column) }
        }
        val column = equation.entries.maxWithOrNull { a, b -> a.value.magnitude().compareTo(b.value.magnitude()) }
            ?.key ?: continue
        val scale = equation.getValue(column).reciprocal()
        for (entry in equation.entries) entry.setValue(entry.value * scale)
        for ((_, pivot) in pivots) {
            if (poll()) return null
            pivot.remove(column)?.let { factor -> pivot.subtract(factor, equation, column) }
        }
        pivots += column to equation
    }
    val repaired = direction.toMutableList()
    for ((column, pivot) in pivots) {
        var step = BigFraction.ZERO
        for ((other, coefficient) in pivot) if (other != column) step -= coefficient * direction[other]
        repaired[column] = step
    }
    return repaired.takeUnless { stop() }
}

// The row over the moved columns when its binary64 coefficients give a zero slope along [direction].
private fun LinearSourceRow.levelEquation(direction: List<BigFraction>, poll: Poll): HashMap<Int, BigFraction>? {
    var projected = BigFraction.ZERO
    val equation = HashMap<Int, BigFraction>()
    for (term in termValues) {
        if (poll()) return null
        val step = direction[term.column]
        if (step.isZero) continue
        val coefficient = term.coefficient.fraction()
        projected += (BigFraction.ofDouble(coefficient.toDouble()) ?: return null) * step
        val sum = (equation[term.column] ?: BigFraction.ZERO) + coefficient
        if (sum.isZero) equation.remove(term.column) else equation[term.column] = sum
    }
    return equation.takeIf { projected.isZero && it.isNotEmpty() }
}

// Subtracts [factor] times [pivot], whose [column] coefficient is one and already removed from this row.
private fun HashMap<Int, BigFraction>.subtract(factor: BigFraction, pivot: Map<Int, BigFraction>, column: Int) {
    for ((other, coefficient) in pivot) {
        if (other == column) continue
        val sum = (this[other] ?: BigFraction.ZERO) - factor * coefficient
        if (sum.isZero) remove(other) else this[other] = sum
    }
}

private fun LinearSourceRow.activeAt(values: List<BigFraction>): Boolean {
    val guard = indicator ?: return true
    return values[guard.column] == if (guard.whenOne) BigFraction.ONE else BigFraction.ZERO
}

private fun slope(terms: List<LinearSourceTerm>, direction: List<BigFraction>, poll: Poll): BigFraction? {
    var slope = BigFraction.ZERO
    for (term in terms) {
        if (poll()) return null
        val step = direction[term.column]
        if (!step.isZero) slope += term.coefficient.fraction() * step
    }
    return slope
}

private class Poll(private val stop: ExecutionControl) {
    private var visits = 0

    operator fun invoke(): Boolean {
        stop.charge(1L)
        return ++visits % RECESSION_POLL_BATCH == 0 && stop()
    }
}

private const val RECESSION_POLL_BATCH = 64

private fun BigFraction.magnitude(): BigFraction = if (signum() < 0) negated() else this

private fun SemanticRational.fraction(): BigFraction = BigFraction.of(numerator, denominator)
