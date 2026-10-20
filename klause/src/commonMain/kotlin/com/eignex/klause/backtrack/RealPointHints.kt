package com.eignex.klause.backtrack

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.exactForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qflra.MixedPointArithmetic
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.toLongExact

internal fun integerPointHints(
    problem: Problem,
    point: List<BigFraction>,
    domain: (Int) -> IntDomain,
    stop: Cancellation,
): LongArray? {
    if (!supportsRealPointHints(problem) || point.size != problem.numRealVars + problem.numIntVars || stop()) {
        return null
    }
    val work = MixedPointArithmetic(stop)
    val values = LongArray(problem.numIntVars)
    for (integer in values.indices) {
        val value = point[problem.numRealVars + integer]
        if (!work.admitted(value)) return null
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength()).toLong()
        val words = bits / Long.SIZE_BITS + 1L
        stop.charge(2L * bits + 4L * words * words)
        if (stop()) return null
        val bounds = domain(integer)
        values[integer] = when {
            value <= BigFraction.ofLong(bounds.min) -> bounds.min
            value >= BigFraction.ofLong(bounds.max) -> bounds.max
            else -> value.ceilInteger().toLongExact()
        }
    }
    return values.takeUnless { stop() }
}

internal fun supportsRealPointHints(problem: Problem): Boolean = problem.numRealVars > 0 &&
    problem.numRealVars.toLong() + problem.numIntVars <= 10000L &&
    problem.numBoolVars in 1..65536 && problem.numFactors <= 100000 &&
    problem.numBoolVars.toLong() + problem.numRealVars + 2L * problem.numIntVars <= 65536L &&
    !problem.semantics.isApproximate

internal fun realPointHints(
    problem: Problem,
    point: List<BigFraction>,
    booleanValue: (Int) -> Boolean?,
    stop: Cancellation,
): IntArray? {
    if (!supportsRealPointHints(problem) || point.size != problem.numRealVars + problem.numIntVars || stop()) {
        return null
    }
    stop.charge(8L * (problem.numBoolVars + problem.numRealVars + problem.numIntVars + problem.numFactors + 1L))
    if (stop()) return null
    val work = MixedPointArithmetic(stop)
    if (!point.all(work::admitted)) return null
    val hints = IntArray(problem.numBoolVars) { -1 }
    var entries = problem.numBoolVars.toLong() + problem.numRealVars + 2L * problem.numIntVars
    for (factor in problem.factors) {
        stop.charge(1L)
        if (stop()) return null
        val form = factor.linearForm as? LinearForm.Conjunction ?: continue
        for (row in form.rows) {
            val head = row.activator
            if (head == LinearRow.ALWAYS) continue
            entries += row.size + 1L
            stop.charge(8L * (row.size + 1L))
            if (entries > 65536L || head !in hints.indices || stop()) return null
            if (hints[head] == -2) continue
            val comparison = row.exactForm(problem.numRealVars)
                .hintComparison(booleanValue, work::admitted, work::preflight)
            if (stop()) return null
            if (comparison == null || comparison.terms.keys.any { it !in point.indices }) {
                hints[head] = -2
                continue
            }
            var slack = comparison.bound
            for ((column, coefficient) in comparison.terms) {
                slack = work.subtractProduct(slack, coefficient, point[column]) ?: return null
            }
            val truth = when (comparison.op) {
                LinearOp.LE -> if (comparison.strict) slack.signum() > 0 else slack.signum() >= 0
                LinearOp.GE -> if (comparison.strict) slack.signum() < 0 else slack.signum() <= 0
                LinearOp.EQ -> slack.isZero
                LinearOp.NE -> !slack.isZero
            }
            val value = if (truth) 1 else 0
            hints[head] = if (hints[head] == -1 || hints[head] == value) value else -2
        }
    }
    return hints.takeUnless { stop() }
}
