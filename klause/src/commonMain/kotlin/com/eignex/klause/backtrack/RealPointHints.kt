package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.ReifiedLinear
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

internal class RealPointHints(
    private val numBooleans: Int,
    private val numIntegers: Int,
    private val numReals: Int,
    private val stop: Cancellation,
) {
    private var booleans: IntArray? = null
    private var integers: LongArray? = null
    private var cursor = 0
    private var refreshPending = false

    val available: Boolean get() = booleans != null
    val needsRefresh: Boolean get() = booleans == null || refreshPending
    val retainedEntries: Int get() = booleans?.size ?: 0

    fun replace(booleanValues: IntArray, integerValues: LongArray) {
        require(booleanValues.size == numBooleans)
        require(integerValues.isEmpty() || integerValues.size == numIntegers)
        booleans = booleanValues
        integers = integerValues
        cursor = 0
        refreshPending = false
    }

    fun booleanValue(variable: Int): Long? = when (booleans?.getOrNull(variable)) {
        0 -> 0L
        1 -> 1L
        else -> null
    }

    fun integerValue(variable: Int): Long? = integers?.getOrNull(variable)

    fun preferredBooleanVariable(accept: (Int) -> Boolean): Int? {
        val values = booleans ?: return null
        while (cursor < values.size) {
            stop.charge(1L)
            if (stop()) return null
            val variable = cursor++
            if ((values[variable] == 0 || values[variable] == 1) && accept(variable)) return variable
        }
        return null
    }

    fun retract(): Boolean {
        integers = null
        if (booleans == null) return false
        // Refresh must admit the retained guesses alongside the replacement workspace.
        if (2L * numBooleans + numReals + 2L * numIntegers > 65536L) {
            clear()
            return false
        }
        refreshPending = true
        return true
    }

    fun clear() {
        booleans = null
        integers = null
        cursor = 0
        refreshPending = false
    }
}

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
    retainedEntries: Int = 0,
    onRoundedBinary: ((Boolean) -> Unit)? = null,
): IntArray? {
    var entries = problem.numBoolVars.toLong() + problem.numRealVars + 2L * problem.numIntVars + retainedEntries
    if (!supportsRealPointHints(problem) || point.size != problem.numRealVars + problem.numIntVars ||
        retainedEntries < 0 || entries > 65536L || stop()
    ) {
        return null
    }
    stop.charge(8L * (problem.numBoolVars + problem.numRealVars + problem.numIntVars + problem.numFactors + 1L))
    if (stop()) return null
    val work = MixedPointArithmetic(stop)
    if (!point.all(work::admitted)) return null
    val hints = IntArray(problem.numBoolVars) { -1 }
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
            val rounded = (factor as? ReifiedLinear)?.let { binaryPointPreference(problem, it, point, work) }
            if (stop()) return null
            if (rounded != null) onRoundedBinary?.invoke(rounded != truth)
            if (stop()) return null
            val value = if (rounded ?: truth) 1 else 0
            hints[head] = if (hints[head] == -1 || hints[head] == value) value else -2
        }
    }
    return hints.takeUnless { stop() }
}

private fun binaryPointPreference(
    problem: Problem,
    channel: ReifiedLinear,
    point: List<BigFraction>,
    work: MixedPointArithmetic,
): Boolean? {
    val constants = channel.integerConstants ?: return null
    if (channel.vars.size != 1 || constants.coeff(0) != 1L || constants.bound !in 0L..1L ||
        channel.op !in setOf(LinearOp.EQ, LinearOp.NE)
    ) return null
    val variable = channel.vars[0]
    val bounds = problem.intBounds
    if (!bounds.hasLower(variable) || !bounds.hasUpper(variable) || bounds.lower(variable) != 0L ||
        bounds.upper(variable) != 1L
    ) return null
    val value = point[problem.numRealVars + variable]
    if (value.isInteger()) return null
    val halfSlack = work.subtractProduct(BigFraction.ONE, BigFraction.ofLong(2), value) ?: return null
    val bit = if (halfSlack.signum() < 0) 1L else 0L
    val equal = bit == constants.bound
    return if (channel.op == LinearOp.EQ) equal else !equal
}
