package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus
import kotlin.math.ceil

internal fun ExactLinear.propagationView(): Propagator = when {
    variables.reals.isNotEmpty() -> NoPropagator
    activator != LinearRow.ALWAYS -> WideReifiedLinearPropagator(
        activator, variables.boolVars, intVars, Array(size) { constants.exactCoeff(it) },
        IntArray(size) { Term.intVar(ref(it)) }, relation, constants.exactBound,
    )
    else -> WideLinearPropagator(intVars, IntArray(size) { Term.intVar(ref(it)) },
        Array(size) { constants.exactCoeff(it) }, relation, constants.exactBound)
}

internal fun LinearIndicator.propagationView(): Propagator = if (variables.reals.isNotEmpty()) NoPropagator else
    object : Propagator {
        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val trigger = if (whenOne) 1L else 0L
            val domain = state.intDomains[indicator]
            if (domain.min != domain.max || domain.min != trigger ||
                row.intVars.any { state.intDomains[it].min != state.intDomains[it].max }
            ) return true
            var activity = BIG_ZERO
            for (k in 0 until row.size) {
                activity += row.constants.exactCoeff(k) * bigIntOf(state.intDomains[Term.intVar(row.ref(k))].min)
            }
            return exactLinearResidual(activity - row.constants.exactBound, row.relation) == BIG_ZERO
        }
    }

internal fun ExactLinear.scoringView(): Invariant = if (variables.reals.isEmpty()) {
    ExactLinearInvariant(constants, IntArray(size) { Term.intVar(ref(it)) }, relation, activator)
} else ExactLinearScore(this)

internal fun LinearIndicator.scoringView(): Invariant = ExactLinearScore(row, indicator, whenOne)

private class ExactLinearScore(
    private val row: ExactLinear,
    private val indicator: Int = -1,
    private val whenOne: Boolean = true,
) : Invariant {
    private val scale = run {
        var maximum = bigIntOf(1)
        val bound = row.constants.exactBound.abs()
        if (bound > maximum) maximum = bound
        for (index in 0 until row.size) {
            val magnitude = row.constants.exactCoeff(index).abs()
            if (magnitude > maximum) maximum = magnitude
        }
        maximum
    }
    private val coefficients = DoubleArray(row.size) { BigFraction.of(row.constants.exactCoeff(it), scale).toDouble() }
    private val bound = BigFraction.of(row.constants.exactBound, scale).toDouble()

    private fun score(
        state: LocalSearchState,
        changedInt: Int = -1,
        integer: Long = 0L,
        changedReal: Int = -1,
        real: Double = 0.0,
        flippedBool: Int = -1,
    ): Int {
        fun intValue(id: Int): Long = if (id == changedInt) integer else state.assignment.intValue(id)
        if (indicator >= 0 && intValue(indicator) != (if (whenOne) 1L else 0L)) return 0
        if (row.variables.reals.isEmpty()) {
            var activity = BIG_ZERO
            for (k in 0 until row.size) {
                activity += row.constants.exactCoeff(k) * bigIntOf(intValue(Term.intVar(row.ref(k))))
            }
            val residual = exactLinearResidual(activity - row.constants.exactBound, row.relation)
            val largest = bigIntOf(Long.MAX_VALUE)
            val capped = if (residual > largest) Long.MAX_VALUE else residual.toLongExact()
            return compressViolation(capped, state.violationSoftCap)
        }
        var activity = 0.0
        for (k in 0 until row.size) {
            val ref = row.ref(k)
            val value = if (Term.isInt(ref)) intValue(Term.intVar(ref)).toDouble()
                else if (Term.realVar(ref) == changedReal) real else state.assignment.realValue(Term.realVar(ref))
            activity += coefficients[k] * value
        }
        val difference = activity - bound
        val residual = when (row.relation) {
            LinearOp.LE -> maxOf(0.0, difference)
            LinearOp.GE -> maxOf(0.0, -difference)
            LinearOp.EQ -> kotlin.math.abs(difference)
            LinearOp.NE -> if (difference == 0.0) 1.0 else 0.0
        }
        val holds = when (row.relation) {
            LinearOp.LE -> if (row.strict) difference < 0.0 else difference <= 0.0
            LinearOp.GE -> if (row.strict) difference > 0.0 else difference >= 0.0
            LinearOp.EQ -> difference == 0.0
            LinearOp.NE -> difference != 0.0
        }
        fun bodyDegree(): Int = if (!residual.isFinite()) state.violationSoftCap else
            maxOf(1, compressViolation(ceil(residual * 1_000_000.0).toLong(), state.violationSoftCap))
        if (row.activator == LinearRow.ALWAYS) return if (holds) 0 else bodyDegree()
        val actual = state.assignment.boolValue(row.activator)
        val wanted = if (row.activator == flippedBool) !actual else actual
        return reifiedDegree(wanted, holds, ::bodyDegree)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        score(state, flippedBool = boolVar) - score(state)
    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        score(state) - score(state, flippedBool = boolVar)
    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = score(state) != 0
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state)
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        score(state, intVar, newValue) - score(state)
    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        score(state) - score(state, intVar, oldValue)
    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        score(state, changedReal = realVar, real = newValue) - score(state)
    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int =
        score(state) - score(state, changedReal = realVar, real = oldValue)
}

private fun exactLinearResidual(difference: BigInt, relation: LinearOp): BigInt = when (relation) {
    LinearOp.LE -> if (difference > BIG_ZERO) difference else BIG_ZERO
    LinearOp.GE -> if (difference < BIG_ZERO) -difference else BIG_ZERO
    LinearOp.EQ -> difference.abs()
    LinearOp.NE -> if (difference == BIG_ZERO) bigIntOf(1) else BIG_ZERO
}
