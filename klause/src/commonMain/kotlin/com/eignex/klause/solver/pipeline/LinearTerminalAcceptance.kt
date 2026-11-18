package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.objective.closesDeclaredGap
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.util.ExecutionControl

internal fun LinearOptimizationResult.verifyTerminal(
    original: SourceProofContext,
    cancellation: ExecutionControl,
    verifier: SourceEvidenceVerifier = SourceEvidenceVerifier(original.withLinearAuthority()),
    gap: OptimalityGap? = null,
    gapTolerance: OptimalityTolerance? = original.semantics?.effective?.optimalityTolerance,
): Verification<Unit, Unit> {
    if (scope != original.scope) return Verification.Rejected("linear completion has a different source invocation")
    if (evidence.derivations.any { it.scope != scope }) {
        return Verification.Rejected("linear completion evidence has a different source invocation")
    }
    if (gapClosed) {
        val point = incumbent ?: return Verification.Rejected("gap completion has no incumbent")
        val exact = value ?: return Verification.Rejected("gap completion has no objective value")
        val checkedBound = bound ?: return Verification.Rejected("gap completion has no global bound")
        val boundValue = ((checkedBound.claim as? SourceClaim.Bound)?.value as? SourceValue.Rational)?.value
            ?: return Verification.Rejected("gap completion has no rational global bound")
        val closes = gap?.closes(exact, boundValue) ?: gapTolerance
            .closesDeclaredGap(exact, boundValue, requireNotNull(scope.objective).sense)
        if (!closes) {
            return Verification.Rejected("linear incumbent does not satisfy the declared gap")
        }
        for (claim in listOf(SourceEvidence(scope, SourceClaim.Witness(point), SourceDerivation.SourceWitness),
            checkedBound)) {
            when (val checked = verifier.verify(claim, cancellation)) {
                SourceEvidenceCheck.Checked -> Unit
                is SourceEvidenceCheck.Rejected -> return Verification.Rejected(checked.reason)
                is SourceEvidenceCheck.Unavailable -> return Verification.Indeterminate(checked.reason)
            }
        }
        if (original.linearValue(point, cancellation) != exact || cancellation()) {
            return Verification.Indeterminate("linear gap objective check unavailable")
        }
        return Verification.Accepted(Candidate(Unit, Unit))
    }
    val completed = terminal ?: return Verification.Indeterminate("linear completion has no terminal claim")
    return when (val checked = verifier.verify(completed, cancellation)) {
        SourceEvidenceCheck.Checked -> Verification.Accepted(Candidate(Unit, Unit))
        is SourceEvidenceCheck.Rejected -> Verification.Rejected(checked.reason)
        is SourceEvidenceCheck.Unavailable -> Verification.Indeterminate(checked.reason)
    }
}
