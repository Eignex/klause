package com.eignex.klause.ir

import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.bindStopControl
import com.eignex.klause.util.pollStop

internal class RetainedFactors private constructor(
    val source: Problem,
    val target: Problem,
    private val indices: IntArray,
) {
    fun sourceIndex(targetIndex: Int): Int = indices[targetIndex]
    fun sourceIndices(): IntArray = indices.copyOf()

    companion object {
        fun between(source: Problem, target: Problem, cancellation: ExecutionControl): RetainedFactors? {
            if (target.numFactors >= source.numFactors || source.numBoolVars != target.numBoolVars ||
                source.numIntVars != target.numIntVars || source.numRealVars != target.numRealVars ||
                source.declaredIntDomains !== target.declaredIntDomains ||
                !source.realLower.contentEquals(target.realLower) ||
                !source.realUpper.contentEquals(target.realUpper) || source.settings !== target.settings ||
                source.semantics !== target.semantics || source.hasSymmetryBreaking != target.hasSymmetryBreaking
            ) return null
            val indices = IntArray(target.numFactors)
            val stop = if (cancellation.stopPolling == null) cancellation.bindStopControl() else cancellation
            val meter = stop.accounting
            val sourceQuota = stop.admission
            var next = 0
            for (index in source.factors.indices) {
                meter?.charge(1)
                if (sourceQuota?.exhausted == true) {
                    stop()
                    return null
                }
                if (stop.pollStop()) return null
                if (next < target.numFactors && source.factors[index] === target.factors[next]) {
                    if ((source.impliedFactorMask?.get(index) == true) !=
                        (target.impliedFactorMask?.get(next) == true)
                    ) return null
                    indices[next++] = index
                }
            }
            if (stop()) return null
            return if (next == target.numFactors) RetainedFactors(source, target, indices) else null
        }
    }
}
