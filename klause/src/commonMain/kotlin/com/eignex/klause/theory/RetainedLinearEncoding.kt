package com.eignex.klause.theory

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RetainedFactors
import com.eignex.klause.util.Cancellation

internal class RetainedLinearEncoding(
    private val mapping: RetainedFactors,
    private val target: EncodingTarget,
    private val encoded: Problem,
) {
    fun model(target: EncodingTarget, indices: List<Int>, cancellation: Cancellation): Problem? {
        if (target != this.target) return null
        if (encoded.numRealVars != mapping.source.numRealVars) return null
        val targetIds = IntArray(mapping.source.numFactors) { -1 }
        for (index in indices) targetIds[mapping.sourceIndex(index)] = index
        val factors = ArrayList<Factor>()
        val origins = ArrayList<Int>()
        val covered = BooleanArray(mapping.target.numFactors)
        for (factor in encoded.factors) {
            cancellation.charge(1)
            if (cancellation()) return null
            val row = factor as? EncodedTheoryFactor ?: return null
            val attribution = row.attribution
            val index = targetIds.getOrNull(attribution.sourceFactorIndex) ?: return null
            if (index < 0) continue
            if (row.source !== mapping.source.factors[attribution.sourceFactorIndex]) return null
            if (attribution.integerConditions.isNotEmpty() || attribution.booleanConditions.isNotEmpty() ||
                attribution.auxiliaries.isNotEmpty() || attribution.integerEmbeddings.isNotEmpty() ||
                attribution.realEmbeddings.isNotEmpty()
            ) return null
            factors += row.atFactorIndex(index)
            origins += index
            covered[index] = true
        }
        if (indices.any { !covered[it] }) return null
        return EncodedTheoryModel(mapping.target, factors.toTypedArray(),
            mapping.target.impliedFactorMask?.let { mask -> BooleanArray(factors.size) { mask[origins[it]] } },
            mapping.target.numRealVars)
    }
}
