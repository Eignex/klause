package com.eignex.klause.theory

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem

internal class EncodedTheoryModel(
    source: Problem,
    factors: Array<Factor>,
    implied: BooleanArray?,
    realCount: Int,
    val sourceRealCount: Int = source.encodingSourceRealCount,
) : Problem(source.numBoolVars, source.numIntVars, source.declaredIntDomains, factors,
    implied, source.hasSymmetryBreaking, realCount,
    DoubleArray(realCount) { if (it < source.numRealVars) source.realLower[it] else Double.NEGATIVE_INFINITY },
    DoubleArray(realCount) { if (it < source.numRealVars) source.realUpper[it] else Double.POSITIVE_INFINITY },
    source.settings)

internal val Problem.encodingSourceRealCount: Int get() = (this as? EncodedTheoryModel)?.sourceRealCount ?: numRealVars

internal fun Problem.rebindEncoding(source: Problem, factors: Array<Factor>, implied: BooleanArray?): Problem =
    if (this is EncodedTheoryModel) EncodedTheoryModel(source, factors, implied, numRealVars, sourceRealCount)
    else source.withFactors(factors, implied)

internal class EncodingRealAllocator(first: Int) {
    var count: Int = first
        private set
    fun allocate(): TheoryRealId {
        require(count < Int.MAX_VALUE / 4) { "encoding coordinate overflow" }
        return TheoryRealId(count++)
    }
}
