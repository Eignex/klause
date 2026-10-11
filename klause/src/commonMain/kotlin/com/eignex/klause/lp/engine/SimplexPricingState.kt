package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.IndexedVector

internal class SimplexPricingState(m: Int, numVars: Int) {
    val gamma = DoubleArray(m) { 1.0 }
    val order = IntArray(numVars)
    val scratch = IntArray(numVars)

    fun resetReference() {
        gamma.fill(1.0)
    }

    fun update(alpha: IndexedVector, r: Int) {
        val pivot = alpha[r]
        val tau = gamma[r]
        val pivotSq = pivot * pivot
        // Only the spike's nonzeros can raise a weight: a zero `αᵢ` gives a candidate of zero and the
        // weights never fall below 1, so the rows the spike misses would keep what they have anyway.
        alpha.forEachStored { i, v ->
            if (i != r) {
                val ratio = v / pivot
                val cand = ratio * ratio * tau
                if (cand > gamma[i]) gamma[i] = cand
            }
        }
        gamma[r] = maxOf(tau / pivotSq, 1.0)
    }

    fun correctWeight(row: Int, trueWeight: Double) {
        gamma[row] = trueWeight
    }
}
