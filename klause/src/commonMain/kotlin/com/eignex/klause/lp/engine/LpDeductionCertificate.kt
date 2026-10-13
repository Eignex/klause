package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal interface LpDeductionCertificate {
    fun belongsTo(model: LpModel, observer: LpCertificationObserver? = null): Boolean
    fun exactMultipliers(): List<BigFraction>
    fun exactReducedCost(col: Int): BigFraction
    fun exactObjectiveBound(): BigFraction
    fun dualNonzeroRow(row: Int): Boolean
    fun reducedCostSign(col: Int): Int
    fun objectiveBoundCeil(extraConstant: Long): Long?
    fun improvingGapNonNegative(improvingMax: Long, sourceConstant: Long): Boolean
    fun fixSteps(col: Int, improvingMax: Long, sourceConstant: Long): Long?
    fun rationalFixingWork(col: Int, cutoff: BigFraction): Long {
        val bits = maxOf(Long.SIZE_BITS, exactBits(listOf(cutoff, exactObjectiveBound(), exactReducedCost(col))))
        val words = (bits.toLong() + Long.SIZE_BITS - 1L) / Long.SIZE_BITS
        return 12L * words * words
    }

    fun fixStepsAtCutoff(col: Int, cutoff: BigFraction, sourceConstant: Long): Long? {
        val reduced = exactReducedCost(col)
        if (reduced.isZero) return null
        val cost = if (reduced.signum() < 0) reduced.negated() else reduced
        return ((cutoff - BigFraction.ofLong(sourceConstant) - exactObjectiveBound()) * cost.reciprocal()).floorLong()
    }
}
