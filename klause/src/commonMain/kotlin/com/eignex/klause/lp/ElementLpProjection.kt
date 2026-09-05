package com.eignex.klause.lp

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.IntArrayList

/**
 * LP relaxation. A constant array gives the exact convex hull: a one-hot selector `y_p ∈ [0,1]` per
 * position whose index value is in `idx`'s root box (pinned to 0 when that value left the live
 * domain), with rows `Σ_p y_p = 1`, the index channel `Σ_p (p + off)·y_p = idx`, and the result channel
 * `Σ_p arr[p]·y_p = result`. A variable array keeps the selectors and index channel but relaxes the
 * bilinear result channel with two big-M rows per position forcing `result = arr[p]` when `y_p = 1`.
 * Large arrays are skipped.
 *
 * The selectors enumerate `idx`'s root box and the one-hot row then confines the column to what was
 * enumerated, so over a side the model leaves open the hull would drop positions the model admits —
 * declined outright there. The big-M result channel leans on both boxes it spans and is emitted per
 * position, only where the model states them.
 */
internal fun Element.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (arr.size > MAX_ELEM) return
    if (!builder.statesBothBounds(idx)) return
    val selCols = IntArrayList()
    val positions = IntArrayList()
    emitSelectorsAndIndexChannel(builder, selCols, positions)
    val k = selCols.size
    if (k == 0) return
    if (arrIsVars) emitResultBigM(builder, selCols, positions) else emitResultChannel(builder, selCols, positions)
}

internal fun Element.estimateLpHull(boxes: RootBoxes): LpSizeEstimate? {
    if (arr.size > MAX_ELEM) return null
    if (!boxes.statesBothBounds(idx)) return null
    val box = boxes.domain(idx)
    // The big-M result channel spans the result's box and the entry's, so it arrives only for the
    // positions whose two boxes are both the model's own — sizing it over the rest would budget rows
    // the build declines.
    val linkable = arrIsVars && boxes.statesBothBounds(result)
    var k = 0L
    var linked = 0L
    for (p in arr.indices) {
        if ((p + indexOffset).toLong() !in box) continue
        k++
        if (linkable && boxes.statesBothBounds(arr[p].toInt())) linked++
    }
    if (k == 0L) return null
    // Constant array: Σ y = 1 + index channel + result channel (3 rows). Variable array:
    // Σ y = 1 + index channel + two big-M rows per linked selector.
    return LpSizeEstimate(cols = k, rows = if (arrIsVars) 2L + 2L * linked else 3L)
}

/** The shared one-hot selectors `Σ_p y_p = 1` and index channel `Σ_p (p + off)·y_p = idx`. */
private fun Element.emitSelectorsAndIndexChannel(
    builder: RelaxationBuilder,
    selCols: IntArrayList,
    positions: IntArrayList,
) {
    val off = indexOffset
    val box = builder.rootDomain(idx)
    val live = builder.liveDomain(idx)
    for (p in arr.indices) {
        val idxVal = p + off
        if (idxVal.toLong() !in box) continue
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
    // `M` spans the result's box and the entry's, so it bounds their gap only while both are the
    // model's own; a position over an invented endpoint stays unlinked (sound — the selector is still
    // free and the one-hot row still holds).
    if (!builder.statesBothBounds(result)) return
    val resCol = builder.intColumn(result)
    val rDom = builder.rootDomain(result)
    for (t in 0 until selCols.size) {
        val arrVar = arr[positions[t]].toInt() // entry is a var id when arrIsVars
        if (!builder.statesBothBounds(arrVar)) continue
        val aDom = builder.rootDomain(arrVar)
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
