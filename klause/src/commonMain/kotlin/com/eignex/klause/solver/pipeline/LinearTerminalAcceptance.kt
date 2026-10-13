package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.util.Cancellation

internal fun LinearOptimizationResult.verifyTerminal(
    original: SourceProofContext,
    cancellation: Cancellation,
): Verification<Unit, Unit> {
    if (scope != original.scope) return Verification.Rejected("linear completion has a different source invocation")
    val completed = terminal ?: return Verification.Indeterminate("linear completion has no terminal claim")
    return when (val checked = evidence.verify(completed.claim, original.withLinearAuthority(), cancellation)) {
        SourceEvidenceCheck.Checked -> Verification.Accepted(Candidate(Unit, Unit))
        is SourceEvidenceCheck.Rejected -> Verification.Rejected(checked.reason)
        is SourceEvidenceCheck.Unavailable -> Verification.Indeterminate(checked.reason)
    }
}
