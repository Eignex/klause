package com.eignex.klause.backtrack

import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.validation.FiniteSourceAcceptance
import kotlin.math.nextDown
import kotlin.math.nextUp

internal class SourceObjectiveScore(reported: Double, val exact: BigFraction? = null) {
    val pruningBound: Double = exact?.let { exactDoubleCeiling(it, reported) } ?: reported
    val display: Double = exact?.toDouble()?.takeIf { it.isFinite() } ?: pruningBound
}

private fun exactDoubleCeiling(exact: BigFraction, reported: Double): Double {
    if (exact.isZero) return 0.0
    val approximation = exact.toDouble().takeIf { it.isFinite() } ?: reported
    if (approximation.isFinite()) {
        var ceiling = approximation
        repeat(4) {
            if (checkNotNull(BigFraction.ofDouble(ceiling)) < exact) {
                ceiling = ceiling.nextUp()
                if (!ceiling.isFinite()) return ceiling
            } else {
                val predecessor = ceiling.nextDown()
                if (!predecessor.isFinite() || checkNotNull(BigFraction.ofDouble(predecessor)) < exact) {
                    return ceiling
                }
                ceiling = predecessor
            }
        }
    }
    val magnitude = if (exact.signum() < 0) exact.negated() else exact
    // Dividing separately rounded numerator and denominator need not enclose the rational.
    var below = 0L
    var above = Double.POSITIVE_INFINITY.toRawBits()
    while (above - below > 1L) {
        val middle = below + (above - below) / 2L
        if (checkNotNull(BigFraction.ofDouble(Double.fromBits(middle))) < magnitude) {
            below = middle
        } else {
            above = middle
        }
    }
    if (exact.signum() > 0) return Double.fromBits(above)
    val aboveValue = Double.fromBits(above)
    return if (aboveValue.isFinite() && BigFraction.ofDouble(aboveValue) == magnitude) {
        -aboveValue
    } else {
        -Double.fromBits(below)
    }
}

internal fun exactSourceObjective(
    objective: LinearObjective,
    sample: Sample,
    toleranceCheck: ((Sample) -> Boolean)? = null,
): BigFraction? {
    (toleranceCheck as? FiniteSourceAcceptance)?.objective?.let { return it(sample) }
    // Under tolerance semantics the float reals are the values, so the score is their exact sum.
    val floatReals = if (toleranceCheck == null) null else sample.reals.map { BigFraction.ofDouble(it) }
    val exactReals = sample.exactReals ?: floatReals?.filterNotNull()?.takeIf { it.size == sample.numRealVars }
    if (objective.realCoefficientValues.any { it != 0.0 } && exactReals == null) return null
    var exact = BigFraction.ofLong(objective.constant)
    for (i in 0 until minOf(objective.boolWeightValues.size, sample.numBoolVars)) {
        if (sample.boolValue(i)) exact += BigFraction.ofLong(objective.boolWeightValues[i])
    }
    for (i in 0 until minOf(objective.intCoefficientValues.size, sample.numIntVars)) {
        exact += BigFraction.ofLong(objective.intCoefficientValues[i]) * BigFraction.ofLong(sample.intValue(i))
    }
    if (exactReals != null) {
        for (i in 0 until minOf(objective.realCoefficientValues.size, exactReals.size)) {
            val coefficient = BigFraction.ofDouble(objective.realCoefficientValues[i]) ?: return null
            exact += coefficient * exactReals[i]
        }
    }
    return exact
}

/**
 * What every producer's proposal must satisfy before it can stand as a minimisation incumbent,
 * whatever engine built it.
 *
 * A non-finite score carries no information and `NaN` compares false against every bound, so an
 * unguarded incumbent would take one and never let it go. And on a problem with LP-only continuous
 * columns the incumbent must carry the continuous values a leaf's residual LP validated and attached:
 * a proposal without them came from a producer that neither solved nor certified the reals, so it is
 * neither complete nor sound to surface however good its integer part looks.
 */
internal fun sampleAdmission(
    problem: Problem,
    toleranceCheck: ((Sample) -> Boolean)? = null,
): CandidateVerifier<Sample, Double> = CandidateVerifier { candidate ->
    val certified = candidate.assignment.exactReals?.size ?: 0

    // Tolerance semantics admit float reals the front end's source check accepts; certified reals need no check.
    fun tolerated() = toleranceCheck != null && candidate.assignment.numRealVars == problem.numRealVars &&
        toleranceCheck(candidate.assignment)
    when {
        !candidate.objective.isFinite() -> Verification.Rejected("non-finite objective ${candidate.objective}")

        problem.numRealVars > 0 && certified < problem.numRealVars && !tolerated() ->
            Verification.Rejected("$certified of ${problem.numRealVars} real values certified")

        else -> Verification.Accepted(candidate)
    }
}

/** The versioned incumbent a minimisation run publishes through: [sampleAdmission] decides what may
 *  stand, and only a strict decrease installs. */
internal fun minimizingSampleExchange(
    problem: Problem,
    toleranceCheck: ((Sample) -> Boolean)? = null,
): IncumbentExchange<Sample, SourceObjectiveScore> {
    val admission = sampleAdmission(problem, toleranceCheck)
    return IncumbentExchange(
        improves = { candidate, standing ->
            if (candidate.exact != null && standing.exact != null) {
                candidate.exact < standing.exact
            } else {
                candidate.display < standing.display
            }
        },
        verifier = CandidateVerifier { candidate ->
            when (val result = admission.verify(Candidate(candidate.assignment, candidate.objective.display))) {
                is Verification.Accepted -> Verification.Accepted(candidate)
                is Verification.Rejected -> result
                is Verification.Indeterminate -> result
            }
        },
    )
}

