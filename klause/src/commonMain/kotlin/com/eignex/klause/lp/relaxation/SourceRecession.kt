package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.complemented
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.validation.verifyCapturedRealTolerance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf

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
    if (point.numIntVars != numIntVars || point.numBoolVars != numBoolVars) return false
    if (reals.indices.any { !within(reals[it], realLower[it], realUpper[it]) }) return false
    if (!descends(objective, direction)) return false
    return factors.all { factor ->
        factor.variables.reals.all { direction[it].isZero } || keeps(factor, point, direction)
    }
}

/**
 * Whether [point] and [direction] prove [objective] unbounded below over this problem under the tolerance reading
 * of [policy]. [point] must hold every relation within the absolute slack alone, and moving along [direction] must
 * never grow a relation's residual: a row's activity moves only toward its bound side, an absolute value keeps the
 * sign its operand has along the whole ray, a finite membership does not move. Every point of the ray then holds
 * within the slack, whose absolute part does not shrink. A relation this check cannot read is read exactly.
 */
internal fun Problem.provesUnboundedWithinTolerance(
    objective: LinearObjective,
    point: Sample,
    direction: List<BigFraction>,
    policy: SourceTolerance,
    cancellation: ExecutionControl = ExecutionControl.Never,
): Boolean {
    if (policy.isExact) return false
    val reals = point.exactReals ?: return false
    if (reals.size != numRealVars || direction.size != numRealVars) return false
    if (point.numIntVars != numIntVars || point.numBoolVars != numBoolVars) return false
    if (!descends(objective, direction)) return false
    val absolute = SourceTolerance(policy.absolute)
    if (verifyCapturedRealTolerance(this, point, absolute, cancellation) !is Verification.Accepted) return false
    return factors.all { factor ->
        factor.variables.reals.all { direction[it].isZero } || keepsWithinTolerance(factor, point, direction)
    }
}

// Whether [direction] is nonzero, moves no real toward a declared bound and lowers [objective].
private fun Problem.descends(objective: LinearObjective, direction: List<BigFraction>): Boolean {
    if (direction.all { it.isZero }) return false
    for (r in 0 until numRealVars) {
        if (direction[r].signum() < 0 && realLower[r] != Double.NEGATIVE_INFINITY) return false
        if (direction[r].signum() > 0 && realUpper[r] != Double.POSITIVE_INFINITY) return false
    }
    var descent = BigFraction.ZERO
    for (r in 0 until minOf(objective.realCoefficientValues.size, numRealVars)) {
        val coefficient = BigFraction.ofDouble(objective.realCoefficientValues[r]) ?: return false
        descent += coefficient * direction[r]
    }
    return descent.signum() < 0
}

private fun keepsWithinTolerance(factor: Factor, point: Sample, direction: List<BigFraction>): Boolean {
    val reals = checkNotNull(point.exactReals)
    return when (factor) {
        is ExactLinear -> factor.keeps(point, direction, sided = false)
        is LinearIndicator -> point.exactIntValue(factor.indicator) != bigIntOf(if (factor.whenOne) 1L else 0L) ||
            factor.row.keeps(point, direction, sided = false)
        is Linear -> {
            val constants = factor.realConstants ?: return false
            rowKeeps(
                factor.vars, constants.intCoefficients, factor.realVars, constants.realCoefficients,
                factor.op, constants.bound, constants.strict, point, direction, sided = false,
            )
        }
        is RealAbs -> {
            val operand = reals[factor.operand]
            val slope = direction[factor.operand]
            val sign = when {
                operand.signum() >= 0 && slope.signum() >= 0 -> BigFraction.ONE
                operand.signum() <= 0 && slope.signum() <= 0 -> BigFraction.MINUS_ONE
                else -> return false
            }
            direction[factor.result] == sign * slope
        }
        is RealFiniteDomain -> direction[factor.variable].isZero
        else -> keeps(factor, point, direction)
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
        is Distinct -> factor.keeps(point, direction)
        is ExactLinear -> factor.keeps(point, direction)
        is LinearIndicator -> point.exactIntValue(factor.indicator) != bigIntOf(if (factor.whenOne) 1L else 0L) ||
            factor.row.keeps(point, direction)
        is Linear -> {
            val constants = factor.realConstants ?: return false
            rowKeeps(
                factor.vars, constants.intCoefficients, factor.realVars, constants.realCoefficients,
                factor.op, constants.bound, constants.strict, point, direction,
            )
        }

        // The Boolean's pin selects the atom or its exact complement, which flips the side and the strictness.
        is ReifiedRealLinear -> {
            val holds = point.boolValue(factor.aux)
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

        is Power -> {
            val base = factor.base as? Power.Coordinate.Real ?: return false
            val result = factor.result as Power.Coordinate.Real
            when (factor.exponent) {
                0 -> reals[result.id.index] == BigFraction.ONE && direction[result.id.index].isZero
                1 -> reals[result.id.index] == reals[base.id.index] &&
                    direction[result.id.index] == direction[base.id.index]
                else -> false
            }
        }

        is ContinuousProduct -> {
            val choices = factor.finiteChoices
            if (choices != null) {
                if (!direction[choices.operand].isZero) return false
                var admitted = false
                for (index in 0 until choices.size) {
                    val matches = reals[choices.operand] == BigFraction.ofDouble(choices.valueAt(index))
                    admitted = admitted || matches
                    if (choices.hasGuards) {
                        val literal = choices.literalAt(index)
                        if (Lit.evaluate(literal, point.boolValue(Lit.variable(literal))) != matches) return false
                    }
                }
                if (!admitted) return false
            }
            reals[factor.a] * reals[factor.b] == reals[factor.result] &&
                (direction[factor.a] * direction[factor.b]).isZero &&
                direction[factor.result] == direction[factor.a] * reals[factor.b] +
                reals[factor.a] * direction[factor.b]
        }

        // With the integer operand fixed at k, the product is the row `result − k·operand = 0`.
        is RealProduct -> {
            val k = BigFraction.ofLong(point.intValue(factor.intOperand))
            reals[factor.result] == k * reals[factor.realOperand] &&
                direction[factor.result] == k * direction[factor.realOperand]
        }

        is RealArrayMinMax -> {
            val value = reals[factor.result]
            val slope = direction[factor.result]
            factor.xs.all { operand ->
                if (factor.max) value >= reals[operand] && slope >= direction[operand]
                else value <= reals[operand] && slope <= direction[operand]
            } && factor.xs.any { reals[it] == value && direction[it] == slope }
        }

        is RealElement -> {
            val position = factor.position(point.intValue(factor.idx)) ?: return false
            val selected = if (factor.arrIsVars) reals[factor.entries[position]]
            else BigFraction.ofDouble(factor.constants[position]) ?: return false
            val slope = if (factor.arrIsVars) direction[factor.entries[position]] else BigFraction.ZERO
            reals[factor.result] == selected && direction[factor.result] == slope
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
    sided: Boolean = true,
): Boolean {
    val reals = checkNotNull(point.exactReals)
    var activity = BigFraction.ZERO
    var slope = BigFraction.ZERO
    for (i in intVars.indices) {
        val coefficient = BigFraction.ofDouble(intCoefficients.at(i)) ?: return false
        activity += coefficient * BigFraction.ofLong(point.intValue(intVars[i]))
    }
    for (j in realVars.indices) {
        val coefficient = BigFraction.ofDouble(realCoefficients.at(j)) ?: return false
        activity += coefficient * reals[realVars[j]]
        slope += coefficient * direction[realVars[j]]
    }
    val rhs = BigFraction.ofDouble(bound) ?: return false
    val side = activity.compareTo(rhs)
    // Unsided, a non-strict row is read for its movement alone; strict rows and disequalities hold exactly.
    val free = !sided && !strict
    return when (op) {
        LinearOp.LE -> (free || (if (strict) side < 0 else side <= 0)) && slope.signum() <= 0
        LinearOp.GE -> (free || (if (strict) side > 0 else side >= 0)) && slope.signum() >= 0
        LinearOp.EQ -> (free || side == 0) && slope.isZero
        LinearOp.NE -> side != 0 && slope.isZero
    }
}

private fun ExactLinear.keeps(point: Sample, direction: List<BigFraction>, sided: Boolean = true): Boolean {
    var activity = BigFraction.ZERO
    var movement = BigFraction.ZERO
    val reals = point.exactReals ?: return false
    for (index in 0 until size) {
        val coefficient = BigFraction.of(constants.exactCoeff(index), BIG_ONE)
        val reference = ref(index)
        if (Term.isInt(reference)) {
            activity += coefficient * BigFraction.of(point.exactIntValue(Term.intVar(reference)), BIG_ONE)
        } else {
            val id = Term.realVar(reference)
            activity += coefficient * reals[id]
            movement += coefficient * direction[id]
        }
    }
    val bound = BigFraction.of(constants.exactBound, BIG_ONE)
    val truth = activator == LinearRow.ALWAYS || point.boolValue(activator)
    val comparison = if (truth) relation else relation.complemented()
    val strictSide = if (truth) strict else !strict
    val free = !sided && !strictSide
    return when (comparison) {
        LinearOp.LE -> (free || (if (strictSide) activity < bound else activity <= bound)) &&
            movement <= BigFraction.ZERO
        LinearOp.GE -> (free || (if (strictSide) activity > bound else activity >= bound)) &&
            movement >= BigFraction.ZERO
        LinearOp.EQ -> (free || activity == bound) && movement.isZero
        LinearOp.NE -> activity != bound && movement.isZero
    }
}

private fun Distinct.keeps(point: Sample, direction: List<BigFraction>): Boolean {
    val reals = point.exactReals ?: return false
    val values = operands.map { it.value(point::exactIntValue, point::boolValue, reals::get) }
    val rates = operands.map { it.value({ BIG_ZERO }, { false }, direction::get) - it.constant }
    val wanted = activator == LinearRow.ALWAYS || point.boolValue(activator)
    for (first in values.indices) {
        for (second in first + 1 until values.size) {
            val difference = values[first] - values[second]
            val rate = rates[first] - rates[second]
            if (wanted) {
                if (difference.isZero || (!rate.isZero && rate.signum() != difference.signum())) return false
            } else {
                // A finite set of affine collisions covers a ray only if a collision is permanent.
                if (difference.isZero && rate.isZero) return true
            }
        }
    }
    return wanted
}
