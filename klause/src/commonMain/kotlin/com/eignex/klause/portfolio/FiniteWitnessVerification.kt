package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import kotlin.math.abs

internal fun finiteWitnessVerifier(
    problem: BakedProblem,
    objective: LinearObjective?,
    cancellation: Cancellation = Cancellation.Never,
    toleranceCheck: ((Sample) -> Boolean)? = null,
): CandidateVerifier<Sample, Double?> = CandidateVerifier { candidate ->
    val sample = candidate.assignment
    val claimed = candidate.objective
    val certificate = sample.witnessCertificate
    if (certificate != null &&
        (!certificate.model.sameModel(ModelIdentity.of(problem)) || certificate.kind != EvidenceKind.Witness)
    ) {
        return@CandidateVerifier Verification.Rejected("witness certificate belongs to a different model")
    }
    if (sample.bools.size != problem.numBoolVars || sample.numIntVars != problem.numIntVars) {
        return@CandidateVerifier Verification.Rejected("assignment does not cover the model's discrete variables")
    }
    if (sample.exactInts != null && sample.exactInts.any { !it.fitsLong() }) {
        return@CandidateVerifier Verification.Indeterminate("a finite checker cannot verify wide integer coordinates")
    }
    when (val verdict = composedFixpoint(problem, Candidate(sample, Unit), cancellation)) {
        is Verification.Rejected -> return@CandidateVerifier verdict
        is Verification.Indeterminate -> return@CandidateVerifier verdict
        is Verification.Accepted -> Unit
    }
    if (problem.numRealVars > 0 && toleranceCheck != null) {
        if (!toleranceCheck(sample)) {
            return@CandidateVerifier Verification.Rejected("source tolerance check refutes the assignment")
        }
    } else {
        when (val verdict = verifyRealCoordinates(problem, sample)) {
            is Verification.Rejected -> return@CandidateVerifier verdict
            is Verification.Indeterminate -> return@CandidateVerifier verdict
            is Verification.Accepted -> Unit
        }
    }
    if (claimed != null && !claimed.isFinite()) {
        return@CandidateVerifier Verification.Rejected("non-finite objective $claimed")
    }
    val evaluated = if (claimed != null) objective?.evaluate(sample) else null
    if (claimed != null && evaluated != null &&
        abs(claimed - evaluated) > OBJECTIVE_TOLERANCE * maxOf(1.0, abs(evaluated))
    ) {
        Verification.Rejected("objective is $evaluated, not the claimed $claimed")
    } else {
        Verification.Accepted(candidate)
    }
}

// Relative slack between an arm's claimed objective and the one re-evaluated from its assignment.
private const val OBJECTIVE_TOLERANCE = 1e-6
