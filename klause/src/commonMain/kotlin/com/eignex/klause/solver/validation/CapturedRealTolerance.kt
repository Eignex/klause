package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf

internal fun Factor.hasCapturedLinearTolerance(): Boolean =
    this is Linear || this is ExactLinear || this is LinearIndicator

internal fun capturedSourceSlack(
    policy: SourceTolerance?,
    bound: BigFraction,
    magnitude: BigFraction = BigFraction.ZERO,
): BigFraction {
    if (policy == null || policy.isExact) return BigFraction.ZERO
    val absolute = BigFraction.of(policy.absolute.numerator, policy.absolute.denominator)
    val relative = BigFraction.of(policy.relative.numerator, policy.relative.denominator)
    val scaled = relative * maxOf(bound.magnitude(), magnitude)
    return maxOf(absolute, scaled)
}

internal fun verifyCapturedLinearTolerance(
    source: Problem,
    sample: Sample,
    policy: SourceTolerance,
    cancellation: Cancellation,
): Verification<Sample, Unit> {
    val reals = sample.exactReals
        ?: return Verification.Indeterminate("captured tolerance requires rational source coordinates")
    for (id in reals.indices) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("captured tolerance bound check cancelled")
        val lower = BigFraction.ofDouble(source.realLower[id])
        val upper = BigFraction.ofDouble(source.realUpper[id])
        if ((lower != null && reals[id] < lower - capturedSourceSlack(policy, lower)) ||
            (upper != null && reals[id] > upper + capturedSourceSlack(policy, upper))
        ) return Verification.Rejected("captured source tolerance refutes a real declaration")
    }
    for (factor in source.factors) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("captured tolerance factor check cancelled")
        if (!factor.hasCapturedLinearTolerance()) {
            if (factor.variables.reals.isEmpty()) continue
            return Verification.Indeterminate("captured tolerance has no complete source relation adapter")
        }
        val form = if (factor is LinearIndicator) {
            if (sample.exactIntValue(factor.indicator) != bigIntOf(if (factor.whenOne) 1L else 0L)) continue
            LinearForm.Conjunction(listOf(factor.row))
        } else factor.linearForm
        val rows = when (form) {
            is LinearForm.Conjunction -> form.rows
            is LinearForm.Disjunction -> form.rows
            else -> return Verification.Indeterminate("captured tolerance has no complete source relation adapter")
        }
        val checked = ArrayList<Boolean>(rows.size)
        for (row in rows) {
            checked += toleranceRow(row, sample, policy, cancellation)
                ?: return Verification.Indeterminate("captured tolerance row is unavailable or cancelled")
        }
        val valid = if (form is LinearForm.Disjunction) checked.any { it } else checked.all { it }
        if (cancellation()) return Verification.Indeterminate("captured tolerance row check cancelled")
        if (!valid) return Verification.Rejected("captured source tolerance refutes a real relation")
    }
    return if (cancellation()) Verification.Indeterminate("captured tolerance check cancelled")
    else Verification.Accepted(Candidate(sample, Unit))
}

private fun toleranceRow(
    row: LinearRow,
    sample: Sample,
    policy: SourceTolerance,
    cancellation: Cancellation,
): Boolean? {
    if (row.strict || row.activator != LinearRow.ALWAYS || row.relation == LinearOp.NE) return null
    val constants = row.constants
    val bound = when (constants) {
        is IntegralConstants -> BigFraction.of(constants.exactBound, BIG_ONE)
        is RealConstants -> BigFraction.ofDouble(constants.bound) ?: return null
    }
    var activity = BigFraction.ZERO
    var magnitude = BigFraction.ZERO
    for (index in 0 until row.size) {
        cancellation.charge(1)
        if (cancellation()) return null
        val coefficient = when (constants) {
            is IntegralConstants -> BigFraction.of(constants.exactCoeff(index), BIG_ONE)
            is RealConstants -> BigFraction.ofDouble(if (index < constants.intCoefficients.size) {
                constants.intCoefficients.at(index)
            } else constants.realCoefficients.at(index - constants.intCoefficients.size)) ?: return null
        }
        val reference = row.ref(index)
        val value = when {
            Term.isBool(reference) -> {
                val literal = Term.lit(reference)
                if (Lit.evaluate(literal, sample.boolValue(Lit.variable(literal)))) BigFraction.ONE
                else BigFraction.ZERO
            }
            Term.isInt(reference) -> BigFraction.of(sample.exactIntValue(Term.intVar(reference)), BIG_ONE)
            else -> requireNotNull(sample.exactReals)[Term.realVar(reference)]
        }
        val term = coefficient * value
        activity += term
        magnitude += term.magnitude()
    }
    val slack = capturedSourceSlack(policy, bound, magnitude)
    if (cancellation()) return null
    return when (row.relation) {
        LinearOp.LE -> activity <= bound + slack
        LinearOp.GE -> activity >= bound - slack
        LinearOp.EQ -> (activity - bound).magnitude() <= slack
        LinearOp.NE -> null
    }
}

private fun BigFraction.magnitude(): BigFraction = if (this < BigFraction.ZERO) negated() else this
