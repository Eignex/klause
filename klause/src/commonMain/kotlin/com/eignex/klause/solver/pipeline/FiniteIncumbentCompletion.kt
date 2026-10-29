package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.util.Cancellation

internal fun FiniteExecutionRequest.incumbentCompletionCheck(): ((Sample, BigFraction, Cancellation) -> Boolean)? {
    if (numericalCompanionParams?.sourceIncumbentCompletion != true) return null
    val original = sourceInvocation?.linearSource ?: return null
    return original.incumbentCompletionCheck(sourceMapping)
}

internal fun PreparedLinearSource.incumbentCompletionCheck(
    mapping: SourceMapping?,
): (Sample, BigFraction, Cancellation) -> Boolean {
    return { sample, value, token ->
        token.charge(sample.numBoolVars.toLong() + sample.numIntVars + sample.numRealVars +
            mapping?.stages.orEmpty().sumOf { it.rebuild.steps.size.toLong() })
        if (token()) false else {
            val lifted = mapping?.reconstructFrom(mapping.target, sample) ?: sample
            val witness = (check(lifted, token) as? LinearSourceCheck.Accepted)?.witness
            val sourceValue = witness?.objective?.let {
                val scaled = it * view.objectiveScale
                if (view.source.objective?.sense == ObjectiveSense.MAXIMIZE) scaled.negated() else scaled
            }
            val expected = mapping?.objectiveAdjustment?.sourceValue(value) ?: value
            !token() && sourceValue != null && sourceValue == expected
        }
    }
}
