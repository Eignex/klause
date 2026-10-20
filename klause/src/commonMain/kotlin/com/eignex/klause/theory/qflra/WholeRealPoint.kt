package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

internal fun wholeRealPointTruths(
    source: Problem,
    point: List<BigFraction>,
    stop: Cancellation,
): IntArray? {
    if (source.numIntVars != 0 || source.numRealVars !in 1..10000 || source.numBoolVars > 100000 ||
        source.numFactors > 100000 || source.semantics.isApproximate || point.size != source.numRealVars || stop()
    ) return null
    stop.charge(8L * (source.numBoolVars + source.numRealVars + source.numFactors + 1L))
    if (stop()) return null
    val work = MixedPointArithmetic(stop)
    if (!point.all(work::admitted)) return null
    for (variable in point.indices) {
        for (lower in listOf(true, false)) {
            val value = if (lower) source.realLower[variable] else source.realUpper[variable]
            if (value.isNaN()) return null
            if (!value.isFinite()) continue
            val bound = BigFraction.ofDouble(value) ?: return null
            if (!work.admitted(bound)) return null
            val difference = work.subtractProduct(point[variable], BigFraction.ONE, bound) ?: return null
            if (if (lower) difference.signum() < 0 else difference.signum() > 0) return null
        }
    }
    val truths = IntArray(source.numBoolVars) { -1 }
    var entries = 0L
    for (factor in source.factors) {
        stop.charge(1L)
        if (stop()) return null
        if (factor is Clause) continue
        val row = factor as? ExactLinear ?: return null
        entries += row.size + 1L
        if (entries > 1000000L) return null
        stop.charge(8L * (row.size + 1L))
        if (stop()) return null
        val constants = row.constants
        var slack = BigFraction.of(constants.exactBound, BIG_ONE)
        if (!work.admitted(slack)) return null
        for (index in 0 until row.size) {
            val reference = row.ref(index)
            if (!Term.isReal(reference)) return null
            val coordinate = point.getOrNull(Term.realVar(reference)) ?: return null
            val coefficient = BigFraction.of(constants.exactCoeff(index), BIG_ONE)
            if (!work.admitted(coefficient)) return null
            slack = work.subtractProduct(slack, coefficient, coordinate) ?: return null
        }
        val truth = when (row.relation) {
            LinearOp.LE -> if (row.strict) slack.signum() > 0 else slack.signum() >= 0
            LinearOp.GE -> if (row.strict) slack.signum() < 0 else slack.signum() <= 0
            LinearOp.EQ -> slack.isZero
            LinearOp.NE -> !slack.isZero
        }
        if (row.activator == LinearRow.ALWAYS) {
            if (!truth) return null
        } else {
            val value = if (truth) 1 else 0
            val previous = truths.getOrNull(row.activator) ?: return null
            if (previous != -1 && previous != value) return null
            truths[row.activator] = value
        }
    }
    return truths.takeUnless { stop() }
}

internal fun completeWholeRealPoint(
    source: Problem,
    point: List<BigFraction>,
    pins: IntArray,
    stop: Cancellation,
    completeBooleans: (IntArray, Cancellation) -> BooleanArray?,
): ExactLiraAssignment? {
    if (pins.size != source.numBoolVars) return null
    val guesses = wholeRealPointTruths(source, point, stop) ?: return null
    stop.charge(8L * (guesses.size + 1L))
    if (stop()) return null
    for (variable in guesses.indices) {
        val pin = pins[variable]
        if (pin !in -1..1) return null
        if (pin == -1) continue
        if (guesses[variable] != -1 && guesses[variable] != pin) return null
        guesses[variable] = pin
    }
    val expected = guesses.copyOf()
    val complete = completeBooleans(guesses, stop) ?: return null
    if (complete.size != expected.size || stop()) return null
    for (variable in expected.indices) {
        if (expected[variable] != -1 && complete[variable] != (expected[variable] == 1)) return null
    }
    return if (stop()) null else ExactLiraAssignment(complete, emptyArray(), point)
}
