package com.eignex.klause.ir

import com.eignex.klause.util.Cancellation

internal class RetainedFactors private constructor(
    val source: Problem,
    val target: Problem,
    private val indices: IntArray,
) {
    fun sourceIndex(targetIndex: Int): Int = indices[targetIndex]
    fun sourceIndices(): IntArray = indices.copyOf()

    companion object {
        fun between(source: Problem, target: Problem, cancellation: Cancellation): RetainedFactors? {
            if (target.numFactors >= source.numFactors || source.numBoolVars != target.numBoolVars ||
                source.numIntVars != target.numIntVars || source.numRealVars != target.numRealVars ||
                source.declaredIntDomains !== target.declaredIntDomains ||
                !source.realLower.contentEquals(target.realLower) ||
                !source.realUpper.contentEquals(target.realUpper) || source.settings !== target.settings ||
                source.semantics !== target.semantics || source.hasSymmetryBreaking != target.hasSymmetryBreaking
            ) return null
            val indices = IntArray(target.numFactors)
            var next = 0
            for (index in source.factors.indices) {
                cancellation.charge(1)
                if (cancellation()) return null
                if (next < target.numFactors && source.factors[index] === target.factors[next]) {
                    if ((source.impliedFactorMask?.get(index) == true) !=
                        (target.impliedFactorMask?.get(next) == true)
                    ) return null
                    indices[next++] = index
                }
            }
            return if (next == target.numFactors) RetainedFactors(source, target, indices) else null
        }
    }
}
