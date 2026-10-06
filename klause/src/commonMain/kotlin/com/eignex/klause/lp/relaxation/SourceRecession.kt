package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective

/**
 * Whether [point] and [direction] prove [objective] unbounded below over this problem.
 *
 * [direction] moves only the continuous variables, indexed by real variable id, so every discrete value of
 * [point] stays fixed and integrality is kept along the whole ray. The check reads the factors themselves,
 * not an LP built from them: each real bound and each factor over a moved real must hold exactly at [point]
 * and keep holding along `point + t·direction` for every `t ≥ 0`, and [objective] must strictly decrease
 * along it. A factor over a moved real that this check cannot read declines the proof. Discrete-only factors
 * are not re-checked: [point] is a leaf assignment the search already found consistent.
 */
internal fun Problem.provesUnbounded(objective: LinearObjective, point: Sample, direction: List<BigFraction>): Boolean {
    val reals = point.exactReals ?: return false
    if (reals.size != numRealVars || direction.size != numRealVars) return false
    if (point.ints.size != numIntVars || point.bools.size != numBoolVars) return false
    if (direction.all { it.isZero }) return false
    for (r in 0 until numRealVars) {
        if (!within(reals[r], realLower[r], realUpper[r])) return false
        if (direction[r].signum() < 0 && realLower[r] != Double.NEGATIVE_INFINITY) return false
        if (direction[r].signum() > 0 && realUpper[r] != Double.POSITIVE_INFINITY) return false
    }
    var descent = BigFraction.ZERO
    for (r in 0 until minOf(objective.realCoefficients.size, numRealVars)) {
        val coefficient = BigFraction.ofDouble(objective.realCoefficients[r]) ?: return false
        descent += coefficient * direction[r]
    }
    if (descent.signum() >= 0) return false
    return factors.all { factor ->
        factor.variables.reals.all { direction[it].isZero } || keeps(factor, point, direction)
    }
}

private fun within(value: BigFraction, lower: Double, upper: Double): Boolean {
    val lo = if (lower == Double.NEGATIVE_INFINITY) null else BigFraction.ofDouble(lower) ?: return false
    val hi = if (upper == Double.POSITIVE_INFINITY) null else BigFraction.ofDouble(upper) ?: return false
    return (lo == null || value >= lo) && (hi == null || value <= hi)
}

private fun keeps(factor: Factor, point: Sample, direction: List<BigFraction>): Boolean {
    val reals = checkNotNull(point.exactReals)
    return when (factor) {
        is Linear -> {
            val constants = factor.realConstants ?: return false
            rowKeeps(
                factor.vars, constants.intCoefficients, factor.realVars, constants.realCoefficients,
                factor.op, constants.bound, constants.strict, point, direction,
            )
        }

        // The Boolean's pin selects the atom or its exact complement, which flips the side and the strictness.
        is ReifiedRealLinear -> {
            val holds = point.bools[factor.aux]
            val op = when {
                holds -> factor.op
                factor.op == LinearOp.LE -> LinearOp.GE
                else -> LinearOp.LE
            }
            rowKeeps(
                factor.vars, RealConsts(factor.intCoeffs), factor.realVars, RealConsts(factor.realCoeffs),
                op, factor.bound, if (holds) factor.strict else !factor.strict, point, direction,
            )
        }

        // With the integer operand fixed at k, the product is the row `result − k·operand = 0`.
        is RealProduct -> {
            val k = BigFraction.ofLong(point.ints[factor.intOperand])
            reals[factor.result] == k * reals[factor.realOperand] &&
                direction[factor.result] == k * direction[factor.realOperand]
        }

        else -> false
    }
}

private fun rowKeeps(
    intVars: IntArray,
    intCoefficients: RealConsts,
    realVars: IntArray,
    realCoefficients: RealConsts,
    op: LinearOp,
    bound: Double,
    strict: Boolean,
    point: Sample,
    direction: List<BigFraction>,
): Boolean {
    val reals = checkNotNull(point.exactReals)
    var activity = BigFraction.ZERO
    var slope = BigFraction.ZERO
    for (i in intVars.indices) {
        val coefficient = BigFraction.ofDouble(intCoefficients.at(i)) ?: return false
        activity += coefficient * BigFraction.ofLong(point.ints[intVars[i]])
    }
    for (j in realVars.indices) {
        val coefficient = BigFraction.ofDouble(realCoefficients.at(j)) ?: return false
        activity += coefficient * reals[realVars[j]]
        slope += coefficient * direction[realVars[j]]
    }
    val rhs = BigFraction.ofDouble(bound) ?: return false
    val side = activity.compareTo(rhs)
    return when (op) {
        LinearOp.LE -> (if (strict) side < 0 else side <= 0) && slope.signum() <= 0
        LinearOp.GE -> (if (strict) side > 0 else side >= 0) && slope.signum() >= 0
        LinearOp.EQ -> side == 0 && slope.isZero
        LinearOp.NE -> side != 0 && slope.isZero
    }
}
