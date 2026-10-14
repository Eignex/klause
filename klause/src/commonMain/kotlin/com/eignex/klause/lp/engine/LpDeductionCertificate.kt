package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal interface LpDeductionCertificate {
    fun belongsTo(model: LpModel, observer: LpCertificationObserver? = null): Boolean
    fun exactMultipliers(): List<BigFraction>
    fun exactMultiplier(row: Int): BigFraction
    fun exactReducedCost(col: Int): BigFraction
    fun exactObjectiveBound(): BigFraction
    fun integralObjectiveRows(): IntArray? = null
    fun usesRationalArithmetic(): Boolean = false
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

    fun permitsMovement(col: Int, steps: Long, cutoff: Long, sourceConstant: Long): Boolean {
        val reduced = exactReducedCost(col)
        val cost = if (reduced.signum() < 0) reduced.negated() else reduced
        val gap = BigFraction.ofLong(cutoff) - BigFraction.ofLong(sourceConstant) - exactObjectiveBound()
        return cost * BigFraction.ofLong(steps) <= gap
    }

    fun fixStepsForGap(col: Int, gap: BigFraction): Long? {
        val reduced = exactReducedCost(col)
        if (reduced.isZero) return null
        val cost = if (reduced.signum() < 0) reduced.negated() else reduced
        return (gap * cost.reciprocal()).floorLong()
    }

    fun fixStepsAtCutoff(col: Int, cutoff: BigFraction, sourceConstant: Long): Long? =
        fixStepsForGap(col, cutoff - BigFraction.ofLong(sourceConstant) - exactObjectiveBound())
}
