package com.eignex.klause.solver.validation

import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.incumbent.WitnessCheckKey
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.math.abs

internal fun finiteWitnessVerifier(
    problem: BakedProblem,
    objective: LinearObjective?,
    cancellation: Cancellation = Cancellation.Never,
    toleranceCheck: ((Sample) -> Boolean)? = null,
): CandidateVerifier<Sample, Double?> = finiteWitnessVerifier(
    problem, objective, Assumptions.None, cancellation, toleranceCheck,
)

internal fun finiteWitnessVerifier(
    problem: BakedProblem,
    objective: LinearObjective?,
    assumptions: Assumptions,
    cancellation: Cancellation = Cancellation.Never,
    toleranceCheck: ((Sample) -> Boolean)? = null,
    points: FinitePointCheck = FinitePointCheck(problem),
): CandidateVerifier<Sample, Double?> {
    require(points.checksAllOf(problem)) { "point checker does not cover this problem" }
    return CandidateVerifier { candidate ->
        verifyFiniteWitness(problem, objective, assumptions, cancellation, toleranceCheck, points, candidate)
    }
}

private fun verifyFiniteWitness(
    problem: BakedProblem,
    objective: LinearObjective?,
    assumptions: Assumptions,
    cancellation: Cancellation,
    toleranceCheck: ((Sample) -> Boolean)?,
    points: FinitePointCheck,
    candidate: Candidate<Sample, Double?>,
): Verification<Sample, Double?> {
    val sample = candidate.assignment
    val claimed = candidate.objective
    val certificate = sample.witnessCertificate
    if (certificate != null &&
        (!certificate.model.sameModel(ModelIdentity.of(problem)) || certificate.kind != EvidenceKind.Witness)
    ) {
        return Verification.Rejected("witness certificate belongs to a different model")
    }
    if (sample.numBoolVars != problem.numBoolVars || sample.numIntVars != problem.numIntVars) {
        return Verification.Rejected("assignment does not cover the model's discrete variables")
    }
    if (sample.numRealVars != problem.numRealVars ||
        (sample.exactReals == null &&
            (0 until sample.numRealVars).any { !sample.approximateRealValue(it).isFinite() })
    ) {
        return Verification.Rejected("assignment does not cover finite real coordinates")
    }
    val receipt = FiniteWitnessReceipt(problem, objective, toleranceCheck)
    if (sample.receipts.covers(receipt)) {
        checkPointRestrictions(sample, assumptions, cancellation)?.let { return it }
    } else {
        checkSourceObligations(problem, sample, assumptions, cancellation, toleranceCheck, points)?.let { return it }
        if (!cancellation()) sample.receipts.record(receipt)
    }
    if (claimed != null && !claimed.isFinite()) {
        return Verification.Rejected("non-finite objective $claimed")
    }
    val evaluated = if (claimed != null) objective?.evaluate(sample) else null
    return if (claimed != null && evaluated != null &&
        abs(claimed - evaluated) > OBJECTIVE_TOLERANCE * maxOf(1.0, abs(evaluated))
    ) {
        Verification.Rejected("objective is $evaluated, not the claimed $claimed")
    } else {
        Verification.Accepted(candidate)
    }
}

// Relative slack between an arm's claimed objective and the one re-evaluated from its assignment.
private const val OBJECTIVE_TOLERANCE = 1e-6

private fun checkSourceObligations(
    problem: BakedProblem,
    sample: Sample,
    assumptions: Assumptions,
    cancellation: Cancellation,
    toleranceCheck: ((Sample) -> Boolean)?,
    points: FinitePointCheck,
): Verification<Nothing, Nothing>? {
    when (val verdict = points.check(Candidate(sample, Unit), cancellation, assumptions)) {
        is Verification.Rejected -> return verdict
        is Verification.Indeterminate -> return verdict
        is Verification.Accepted -> Unit
    }
    if (problem.numRealVars > 0 && toleranceCheck != null) {
        val admitted = toleranceCheck(sample)
        if (cancellation()) return Verification.Indeterminate("source tolerance check cancelled")
        if (!admitted) return Verification.Rejected("source tolerance check refutes the assignment")
    } else {
        when (val verdict = verifyRealCoordinates(problem, sample)) {
            is Verification.Rejected -> return verdict
            is Verification.Indeterminate -> return verdict
            is Verification.Accepted -> Unit
        }
    }
    return null
}

/*
 * Everything a finite source check reads besides the coordinates and the caller's restrictions, which are checked
 * on the point for every consumer. Identities, not structure: a transformed or rebuilt model needs its own check.
 */
private class FiniteWitnessReceipt(
    private val problem: BakedProblem,
    private val objective: LinearObjective?,
    private val tolerance: ((Sample) -> Boolean)?,
) : WitnessCheckKey {
    override fun sameCheck(other: WitnessCheckKey): Boolean = other is FiniteWitnessReceipt &&
        problem === other.problem && objective === other.objective && tolerance === other.tolerance
}
