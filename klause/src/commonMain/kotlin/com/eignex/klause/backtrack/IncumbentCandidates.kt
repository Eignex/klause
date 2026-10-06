package com.eignex.klause.backtrack

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
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
    // Under tolerance semantics the float reals are the values, so the score is their exact sum.
    val floatReals = if (toleranceCheck == null) null else sample.reals.map { BigFraction.ofDouble(it) }
    val exactReals = sample.exactReals ?: floatReals?.filterNotNull()?.takeIf { it.size == sample.reals.size }
    if (objective.realCoefficients.any { it != 0.0 } && exactReals == null) return null
    var exact = BigFraction.ofLong(objective.constant)
    for (i in 0 until minOf(objective.boolWeights.size, sample.bools.size)) {
        if (sample.bools[i]) exact += BigFraction.ofLong(objective.boolWeights[i])
    }
    for (i in 0 until minOf(objective.intCoefficients.size, sample.ints.size)) {
        exact += BigFraction.ofLong(objective.intCoefficients[i]) * BigFraction.ofLong(sample.ints[i])
    }
    if (exactReals != null) {
        for (i in 0 until minOf(objective.realCoefficients.size, exactReals.size)) {
            val coefficient = BigFraction.ofDouble(objective.realCoefficients[i]) ?: return null
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
    fun tolerated() = toleranceCheck != null && candidate.assignment.reals.size == problem.numRealVars &&
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

/**
 * Finite composed verification of an untrusted proposal: re-derive the assignment's feasibility from
 * the whole factor set instead of trusting the producer that built it.
 *
 * A heuristic proposes from its own partial view — a relaxation, a rounding, a dive under branch
 * decisions — and its notion of feasible is its own. This pins every Boolean and integer variable of a
 * fresh session to the proposed value and requires the composed fixpoint to survive: a wipeout refutes
 * that one proposal (never the model), and a fixpoint cut short by [cancellation] decides nothing, so
 * it reports [Verification.Indeterminate] rather than pass a half-propagated state off as feasible.
 *
 * The claimed objective is re-evaluated from the assignment too, so the exchange weighs an improvement
 * the verifier computed rather than one the producer asserted.
 *
 * The composed model is the finite one, so this decides the Boolean and integer columns. LP-only
 * continuous columns live outside it: [sampleAdmission] requires the values a leaf's residual LP
 * certified to be attached already, and nothing here re-derives them.
 */
internal class ComposedSampleVerifier(
    private val problem: Problem,
    private val objective: LinearObjective,
    private val cancellation: Cancellation,
) : CandidateVerifier<Sample, Double> {

    private val admission = sampleAdmission(problem)

    override fun verify(candidate: Candidate<Sample, Double>): Verification<Sample, Double> {
        val admitted = admission.verify(candidate)
        if (admitted !is Verification.Accepted) return admitted
        val sample = candidate.assignment
        if (sample.bools.size != problem.numBoolVars || sample.ints.size != problem.numIntVars) {
            return Verification.Rejected(
                "assignment covers ${sample.bools.size}/${sample.ints.size} of the " +
                    "${problem.numBoolVars}/${problem.numIntVars} discrete variables",
            )
        }
        val evaluated = objective.evaluate(sample)
        if (evaluated != candidate.objective) {
            return Verification.Rejected("objective is $evaluated, not the claimed ${candidate.objective}")
        }
        return composedFixpoint(problem, candidate, cancellation)
    }
}

/**
 * Pin the whole assignment into a fresh session, under [assumptions], and read the composed verdict off
 * the fixpoint. A wipeout refutes the assignment; a fixpoint cut short by [cancellation] decides nothing.
 */
internal fun <V> composedFixpoint(
    problem: Problem,
    candidate: Candidate<Sample, V>,
    cancellation: Cancellation,
    assumptions: Assumptions = Assumptions.None,
): Verification<Sample, V> {
    val sample = candidate.assignment
    val session = PropagationSession(problem, cancellation)
    if (session.isUnsatAtRoot) return Verification.Rejected("root propagation refutes the assignment")
    if (!assumptions.isEmpty && session.seed(assumptions) is PropagationResult.Unsat) {
        return Verification.Rejected("the assumptions refute the assignment")
    }
    for (b in 0 until problem.numBoolVars) {
        if (session.pinBool(b, sample.bools[b]) is PropagationResult.Unsat) {
            return Verification.Rejected("bool $b = ${sample.bools[b]} conflicts")
        }
    }
    for (v in 0 until problem.numIntVars) {
        if (session.pinInt(v, sample.ints[v]) is PropagationResult.Unsat) {
            return Verification.Rejected("int $v = ${sample.ints[v]} conflicts")
        }
    }
    // Sticky across every fixpoint above: a cut one leaves the state under-propagated, so a
    // surviving assignment proves nothing.
    if (session.fixpointCancelled) return Verification.Indeterminate("propagation cancelled")
    return Verification.Accepted(candidate)
}
