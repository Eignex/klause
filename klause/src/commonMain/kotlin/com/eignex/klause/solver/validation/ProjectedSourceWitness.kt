package com.eignex.klause.solver.validation

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation

internal fun verifyProjectedSourceWitness(
    source: BakedProblem,
    sample: Sample,
    cancellation: Cancellation,
    toleranceCheck: ((Sample) -> Boolean)?,
): Verification<Sample, Unit> {
    if (cancellation()) return Verification.Indeterminate("source verification cancelled")
    if (sample.numBoolVars != source.numBoolVars || sample.numIntVars != source.numIntVars ||
        sample.numRealVars != source.numRealVars
    ) return Verification.Rejected("assignment does not cover original source coordinates")
    for (id in 0 until source.numIntVars) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("source bounds check cancelled")
        val value = sample.intValue(id)
        if (value < source.intBounds.lower(id) || value > source.intBounds.upper(id) ||
            source.intDomainOrNull(id)?.let { value !in it } == true
        ) return Verification.Rejected("assignment violates an original integer declaration")
    }
    val checkedRealFactors = HashSet<Int>()
    for (id in source.factors.indices) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("source factor check cancelled")
        if (source.factors[id].variables.reals.isNotEmpty()) checkedRealFactors += id
    }
    if (toleranceCheck == null) {
        when (val checked = verifyRealCoordinates(source, sample, cancellation)) {
            is Verification.Accepted -> Unit
            is Verification.Rejected -> return checked
            is Verification.Indeterminate -> return checked
        }
    } else if (!toleranceCheck(sample)) {
        return Verification.Rejected("original source acceptance policy refutes the assignment")
    }
    if (cancellation()) return Verification.Indeterminate("source verification cancelled")
    return composedFixpoint(source, Candidate(sample, Unit), cancellation,
        enforcingPeers = checkedRealFactors, onWork = { cancellation.charge(it) })
}
