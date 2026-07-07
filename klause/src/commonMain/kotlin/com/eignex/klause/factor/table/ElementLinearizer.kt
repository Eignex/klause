package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.lp.LinearizerEstimate
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.IntArrayList

/**
 * LP relaxation of one [Element] `result = arr[idx − indexOffset]`. A *constant* array gives the exact
 * convex hull: a one-hot selector `y_p ∈ [0,1]` per position whose index value is in `idx`'s declared
 * domain (pinned to 0 when that value left the live domain), with rows `Σ_p y_p = 1`, the index channel
 * `Σ_p (p + off)·y_p = idx`, and the result channel `Σ_p arr[p]·y_p = result`. A *variable* array keeps
 * the selectors and index channel but relaxes the bilinear result channel with two big-M rows per
 * position forcing `result = arr[p]` when `y_p = 1` (`M_p = max(rHi, aHi) − min(rLo, aLo)` from the
 * declared domains, a global bound on `|result − arr[p]|`). Arrays longer than [MAX_ELEM] are skipped.
 */
internal class ElementLinearizer(
    private val idx: Int,
    private val result: Int,
    private val arr: IntArray,
    private val arrIsVars: Boolean,
    private val indexOffset: Int,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        if (arr.size > MAX_ELEM) return
        val selCols = IntArrayList()
        val positions = IntArrayList()
        selectorsAndIndexChannel(builder, selCols, positions)
        val k = selCols.size
        if (k == 0) return
        if (arrIsVars) resultBigM(builder, selCols, positions) else resultChannel(builder, selCols, positions)
    }

    override fun sizeEstimate(domains: Array<IntDomain>): LinearizerEstimate? {
        if (arr.size > MAX_ELEM) return null
        val declared = domains[idx]
        var k = 0L
        for (p in arr.indices) if ((p + indexOffset).toLong() in declared) k++
        if (k == 0L) return null
        // Constant array: Σ y = 1 + index channel + result channel (3 rows). Variable array:
        // Σ y = 1 + index channel + two big-M rows per selector (2 + 2k).
        return LinearizerEstimate(cols = k, rows = if (arrIsVars) 2L + 2L * k else 3L)
    }

    /** The shared one-hot selectors `Σ_p y_p = 1` and index channel `Σ_p (p + off)·y_p = idx`. */
    private fun selectorsAndIndexChannel(builder: RelaxationBuilder, selCols: IntArrayList, positions: IntArrayList) {
        val off = indexOffset
        val declared = builder.declaredDomain(idx)
        val live = builder.liveDomain(idx)
        for (p in 0 until arr.size) {
            val idxVal = p + off
            if (idxVal.toLong() !in declared) continue
            selCols.add(
                builder.auxColumn(0L, if (idxVal.toLong() in live) 1L else 0L, presence = intArrayOf(idx, idxVal)),
            )
            positions.add(p)
        }
        val k = selCols.size
        if (k == 0) return
        builder.row(selCols.toIntArray(), LongArray(k) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        val idxCols = IntArray(k + 1)
        val idxVals = LongArray(k + 1)
        for (t in 0 until k) {
            idxCols[t] = selCols[t]
            idxVals[t] = (positions[t] + off).toLong()
        }
        idxCols[k] = builder.intColumn(idx)
        idxVals[k] = -1L
        builder.row(idxCols, idxVals, LinearOp.EQ, 0L, Contribution.HULL)
    }

    /** Constant array: `Σ_p arr[p]·y_p − result = 0` — the exact convex hull of the table. */
    private fun resultChannel(builder: RelaxationBuilder, selCols: IntArrayList, positions: IntArrayList) {
        val k = selCols.size
        val resCols = IntArray(k + 1)
        val resVals = LongArray(k + 1)
        for (t in 0 until k) {
            resCols[t] = selCols[t]
            resVals[t] = arr[positions[t]].toLong()
        }
        resCols[k] = builder.intColumn(result)
        resVals[k] = -1L
        builder.row(resCols, resVals, LinearOp.EQ, 0L, Contribution.HULL)
    }

    /** Variable array: two big-M rows per position tying `result` to `arr[p]` when its selector is on. */
    private fun resultBigM(builder: RelaxationBuilder, selCols: IntArrayList, positions: IntArrayList) {
        val resCol = builder.intColumn(result)
        val rDom = builder.declaredDomain(result)
        for (t in 0 until selCols.size) {
            val arrVar = arr[positions[t]]
            val aDom = builder.declaredDomain(arrVar)
            val m = maxOf(rDom.max, aDom.max) - minOf(rDom.min, aDom.min)
            if (m < 0L) continue // empty domain — leave that position unconstrained (sound)
            val arrCol = builder.intColumn(arrVar)
            val y = selCols[t]
            // result − arr[p] + M·y_p ≤ M  ⇒  result ≤ arr[p] when y_p = 1, slack otherwise.
            builder.row(intArrayOf(resCol, arrCol, y), longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
            // arr[p] − result + M·y_p ≤ M  ⇒  arr[p] ≤ result when y_p = 1, slack otherwise.
            builder.row(intArrayOf(arrCol, resCol, y), longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
        }
    }

    companion object {
        /** Arrays longer than this are skipped — the added selector columns would dominate. */
        const val MAX_ELEM: Int = 256
    }
}
