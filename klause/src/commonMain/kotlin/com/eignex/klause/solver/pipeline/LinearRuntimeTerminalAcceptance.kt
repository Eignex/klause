package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.util.Cancellation

internal fun LinearOptimizationResult.verifyRuntimeTerminal(
    expected: EngineInvocation,
    produced: EngineInvocation,
    original: SourceProofContext,
    cancellation: Cancellation,
): Verification<Unit, Unit> {
    if (!expected.matches(produced)) {
        return Verification.Rejected("linear completion belongs to another runtime invocation")
    }
    if (expected.scope == null) {
        return Verification.Indeterminate("linear runtime lacks original source capture")
    }
    if (expected.scope != original.scope || produced.scope != scope) {
        return Verification.Rejected("linear source capture differs from its runtime invocation")
    }
    if (expected.hasUncapturedSourceRestrictions) {
        return Verification.Indeterminate("linear capture omits runtime source restrictions")
    }
    val base = when (val claim = terminal?.claim) {
        is SourceClaim.Optimal -> claim.sample
        is SourceClaim.Limit -> claim.sample
        is SourceClaim.Ray -> claim.sample
        else -> null
    }
    for (sample in listOfNotNull(base, incumbent?.takeUnless { it === base })) {
        when (val checked = expected.checkWitness(sample, cancellation)) {
            SourceEvidenceCheck.Checked -> Unit
            is SourceEvidenceCheck.Rejected -> return Verification.Rejected(checked.reason)
            is SourceEvidenceCheck.Unavailable -> return Verification.Indeterminate(checked.reason)
        }
    }
    return verifyTerminal(original, cancellation)
}
