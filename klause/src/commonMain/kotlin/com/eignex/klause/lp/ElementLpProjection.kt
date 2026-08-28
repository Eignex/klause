package com.eignex.klause.lp

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.IntArrayList

/**
 * LP relaxation. A constant array gives the exact convex hull: a one-hot selector `y_p ∈ [0,1]` per
 * position whose index value is in `idx`'s declared domain (pinned to 0 when that value left the live
 * domain), with rows `Σ_p y_p = 1`, the index channel `Σ_p (p + off)·y_p = idx`, and the result channel
 * `Σ_p arr[p]·y_p = result`. A variable array keeps the selectors and index channel but relaxes the
 * bilinear result channel with two big-M rows per position forcing `result = arr[p]` when `y_p = 1`.
 * Large arrays are skipped.
 */
internal fun Element.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (arr.size > MAX_ELEM) return
    val selCols = IntArrayList()
    val positions = IntArrayList()
    emitSelectorsAndIndexChannel(builder, selCols, positions)
    val k = selCols.size
    if (k == 0) return
    if (arrIsVars) emitResultBigM(builder, selCols, positions) else emitResultChannel(builder, selCols, positions)
}

internal fun Element.estimateLpHull(domains: Array<IntDomain>): LpSizeEstimate? {
    if (arr.size > MAX_ELEM) return null
    val declared = domains[idx]
    var k = 0L
    for (p in arr.indices) if ((p + indexOffset).toLong() in declared) k++
    if (k == 0L) return null
    // Constant array: Σ y = 1 + index channel + result channel (3 rows). Variable array:
    // Σ y = 1 + index channel + two big-M rows per selector (2 + 2k).
    return LpSizeEstimate(cols = k, rows = if (arrIsVars) 2L + 2L * k else 3L)
}

/** The shared one-hot selectors `Σ_p y_p = 1` and index channel `Σ_p (p + off)·y_p = idx`. */
private fun Element.emitSelectorsAndIndexChannel(
    builder: RelaxationBuilder,
    selCols: IntArrayList,
    positions: IntArrayList,
) {
    val off = indexOffset
    val declared = builder.declaredDomain(idx)
    val live = builder.liveDomain(idx)
    for (p in arr.indices) {
        val idxVal = p + off
        if (idxVal.toLong() !in declared) continue
        selCols.add(
            builder.auxColumn(
                0L,
                if (idxVal.toLong() in live) 1L else 0L,
                presence = longArrayOf(idx.toLong(), idxVal.toLong()),
            ),
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
private fun Element.emitResultChannel(builder: RelaxationBuilder, selCols: IntArrayList, positions: IntArrayList) {
    val k = selCols.size
    val resCols = IntArray(k + 1)
    val resVals = LongArray(k + 1)
    for (t in 0 until k) {
        resCols[t] = selCols[t]
        resVals[t] = arr[positions[t]] // constant value, already Long
    }
    resCols[k] = builder.intColumn(result)
    resVals[k] = -1L
    builder.row(resCols, resVals, LinearOp.EQ, 0L, Contribution.HULL)
}

/** Variable array: two big-M rows per position tying `result` to `arr[p]` when its selector is on. */
private fun Element.emitResultBigM(builder: RelaxationBuilder, selCols: IntArrayList, positions: IntArrayList) {
    val resCol = builder.intColumn(result)
    val rDom = builder.declaredDomain(result)
    for (t in 0 until selCols.size) {
        val arrVar = arr[positions[t]].toInt() // entry is a var id when arrIsVars
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

/** Arrays longer than this are skipped — the added selector columns would dominate. */
private const val MAX_ELEM: Int = 256
