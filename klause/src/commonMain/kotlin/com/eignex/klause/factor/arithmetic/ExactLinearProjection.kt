package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
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

internal fun ExactLinear.propagationView(): Propagator {
    if (variables.reals.isNotEmpty()) return NoPropagator
    val vars = IntArray(size) { Term.intVar(ref(it)) }
    val integer = (constants as? IntegerConstants)?.takeIf {
        it.permitsCompactPropagation(activator != LinearRow.ALWAYS)
    }
    return when {
        integer != null && activator == LinearRow.ALWAYS ->
            LinearPropagator(variables.boolVars, intVars, integer.coeffs, vars, relation, integer.bound)
        integer != null -> ReifiedLinearPropagator(
            activator, variables.boolVars, intVars, integer.coeffs, vars, relation, integer.bound,
        )
        activator != LinearRow.ALWAYS -> WideReifiedLinearPropagator(
            activator, variables.boolVars, intVars, Array(size) { constants.exactCoeff(it) },
            vars, relation, constants.exactBound,
        )
        else -> WideLinearPropagator(intVars, vars, Array(size) { constants.exactCoeff(it) },
            relation, constants.exactBound)
    }
}

private fun IntegerConstants.permitsCompactPropagation(reified: Boolean): Boolean {
    // Endpoint complements can escape Long, and a widened interval cannot certify either endpoint.
    if (reified && (bound == Long.MIN_VALUE || bound == Long.MAX_VALUE)) return false
    // This coefficient budget keeps activity and residuals inside signed 128 bits even on full Long domains.
    var remaining = Long.MAX_VALUE
    for (index in 0 until coefficients.size) {
        val coefficient = coeff(index)
        if (coefficient == Long.MIN_VALUE) return false
        val magnitude = if (coefficient < 0L) -coefficient else coefficient
        if (magnitude > remaining) return false
        remaining -= magnitude
    }
    return true
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

internal fun ExactLinear.scoringView(domains: Array<IntDomain>? = null): Invariant {
    if (variables.reals.isNotEmpty()) return MixedExactLinearScore(this)
    val vars = IntArray(size) { Term.intVar(ref(it)) }
    val integer = constants as? IntegerConstants
    return if (integer != null && domains != null && !needsExactSum(integer, vars, domains)) {
        if (activator == LinearRow.ALWAYS) LinearInvariant(integer.coeffs, vars, relation, integer.bound)
        else ReifiedLinearInvariant(activator, integer.coeffs, vars, relation, integer.bound)
    } else ExactLinearInvariant(constants, vars, relation, activator)
}

internal fun LinearIndicator.scoringView(): Invariant = if (row.variables.reals.isEmpty()) {
    IntegerIndicatorScore(row, indicator, whenOne)
} else MixedExactLinearScore(row, indicator, whenOne)

private class IntegerIndicatorScore(
    private val row: ExactLinear,
    private val indicator: Int,
    private val whenOne: Boolean,
) : Invariant {
    private fun score(state: LocalSearchState, changedInt: Int = -1, integer: Long = 0L): Int {
        fun intValue(id: Int): Long = if (id == changedInt) integer else state.assignment.intValue(id)
        if (intValue(indicator) != (if (whenOne) 1L else 0L)) return 0
        var activity = BIG_ZERO
        for (k in 0 until row.size) {
            activity += row.constants.exactCoeff(k) * bigIntOf(intValue(Term.intVar(row.ref(k))))
        }
        val residual = exactLinearResidual(activity - row.constants.exactBound, row.relation)
        val capped = if (residual > bigIntOf(Long.MAX_VALUE)) Long.MAX_VALUE else residual.toLongExact()
        return compressViolation(capped, state.violationSoftCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = score(state) != 0
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state)
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        score(state, intVar, newValue) - score(state)
    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        score(state) - score(state, intVar, oldValue)
}

private fun exactLinearResidual(difference: BigInt, relation: LinearOp): BigInt = when (relation) {
    LinearOp.LE -> if (difference > BIG_ZERO) difference else BIG_ZERO
    LinearOp.GE -> if (difference < BIG_ZERO) -difference else BIG_ZERO
    LinearOp.EQ -> difference.abs()
    LinearOp.NE -> if (difference == BIG_ZERO) bigIntOf(1) else BIG_ZERO
}
