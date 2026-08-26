package com.eignex.klause.lp.engine

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * A row-capped neighborhood restriction of an [LpModel]: the sub-model over the rows a breadth-first
 * walk of the column/row bipartite graph reaches from the seed columns before the row cap is hit,
 * with every structural column those rows touch. Only **whole rows** are dropped — an accepted row
 * keeps its full support, so the sub-model is a pure relaxation of the original: its feasible set
 * projects onto a superset of the original's, and any bound proved on it (a probe optimum, a dual
 * bound, a Farkas refutation of the sub-rows) is valid on the full model. That is the contract
 * callers lean on: optimization-based bound tightening probes a variable's local constraint
 * structure at a factorization cost bounded by the row cap instead of the full model's row count.
 *
 * [colMap] maps original structural columns to sub-model columns (`-1` when outside the
 * neighborhood) and [rows] lists the taken rows by original index (sub-model row `r` is original row
 * `rows(r)`); bounds, shifts, probe-clamp flags, tags, strictness, global validity, premises and the
 * double view all carry over per selected row/column.
 *
 * The same restriction ([LpModel.restrictTo]) also carves a **connected component** out of a
 * separable model — there the dropped rows share no columns with the kept ones, so the sub-model is
 * not merely a relaxation but exact, and per-component optima stitch back to the full optimum
 * ([ComponentLpSolver]).
 */
internal class LpNeighborhood(
    val model: LpModel,
    /** Old→new structural column map (`-1` outside), or null when the caller tracks columns via
     *  [cols] alone — per-part full-length maps would cost `n × parts` on a decomposed model. */
    val colMap: IntArray?,
    /** Taken rows by original index: sub-model row `r` is original row `rows(r)`. */
    val rows: IntArray,
    /** Taken structural columns by original index: sub-model column `c` is original column `cols(c)`. */
    val cols: IntArray,
) {
    /** Sub-model column of original column [j] (`-1` outside); requires the old→new map. */
    fun colOf(j: Int): Int = checkNotNull(colMap) { "this restriction tracks columns via cols only" }[j]
}

/** Row-major adjacency of a model's structural entries (`rows -> columns`), built once in `O(nnz)`
 *  and shared across many [columnNeighborhood] walks. Covers the union of the [Long] core's and the
 *  double view's sparsity — the two patterns may differ on mixed models (real coefficients live only
 *  in the view; differing zero-coalescing can go either way), and the walk's whole-row-support
 *  soundness needs every entry of either. */
internal class LpRowIndex(val rowPtr: IntArray, val colIdx: IntArray)

/** Build the row-major union adjacency of this model's structural columns. */
internal fun LpModel.rowIndex(): LpRowIndex {
    // Deduplicate per column across the two patterns (rows ascending within each), then bucket by row.
    val counts = IntArray(m + 1)
    forEachUnionEntry { _, i -> counts[i + 1]++ }
    for (i in 0 until m) counts[i + 1] += counts[i]
    val rowPtr = counts.copyOf()
    val colIdx = IntArray(rowPtr[m])
    val cursor = rowPtr.copyOf()
    forEachUnionEntry { j, i -> colIdx[cursor[i]++] = j }
    return LpRowIndex(rowPtr, colIdx)
}

/** Iterate the union-sparsity rows of column [j] once each, ascending. */
internal inline fun LpModel.forEachColumnRow(j: Int, action: (i: Int) -> Unit) {
    val view = doubleView
    if (view == null) {
        for (k in csc.colPtr[j] until csc.colPtr[j + 1]) action(csc.rowIdx[k])
        return
    }
    var a = csc.colPtr[j]
    var b = view.colPtr[j]
    val aEnd = csc.colPtr[j + 1]
    val bEnd = view.colPtr[j + 1]
    while (a < aEnd || b < bEnd) {
        val ra = if (a < aEnd) csc.rowIdx[a] else Int.MAX_VALUE
        val rb = if (b < bEnd) view.rowIdx[b] else Int.MAX_VALUE
        when {
            ra < rb -> {
                action(ra)
                a++
            }

            rb < ra -> {
                action(rb)
                b++
            }

            else -> {
                action(ra)
                a++
                b++
            }
        }
    }
}

/** Iterate every `(column, row)` of the union sparsity once, columns outer. */
internal inline fun LpModel.forEachUnionEntry(action: (j: Int, i: Int) -> Unit) {
    for (j in 0 until n) forEachColumnRow(j) { i -> action(j, i) }
}

/**
 * Extract the [maxRows]-capped neighborhood of [seeds] (structural columns) as its own [LpModel];
 * see [LpNeighborhood] for the relaxation contract. The walk alternates column→rows (this model's
 * CSC) and row→columns ([rowIndex]); a row past the cap is dropped whole, never truncated. The
 * sub-model's costs are zero — a probing caller installs its objective via
 * [LpModel.withSingleColumnObjective] on the mapped columns.
 */
internal fun LpModel.columnNeighborhood(seeds: IntArray, maxRows: Int, rowIndex: LpRowIndex): LpNeighborhood {
    val colMap = IntArray(n) { -1 }
    val takenRows = IntArrayList()
    val takenCols = IntArrayList()
    val rowSeen = IntHashSet()
    val colQueue = ArrayDeque<Int>()
    fun takeCol(j: Int) {
        if (colMap[j] >= 0) return
        colMap[j] = takenCols.size
        takenCols.add(j)
        colQueue.addLast(j)
    }
    for (s in seeds) takeCol(s)
    var capped = false
    while (colQueue.isNotEmpty() && !capped) {
        val j = colQueue.removeFirst()
        // Column→rows over the union sparsity (the double view may carry entries the Long core lacks).
        forEachColumnRow(j) { i ->
            if (i !in rowSeen && !capped) {
                if (takenRows.size >= maxRows) {
                    capped = true
                } else {
                    rowSeen.add(i)
                    takenRows.add(i)
                    // Soundness: an accepted row keeps its whole support — every column it touches enters.
                    for (p in rowIndex.rowPtr[i] until rowIndex.rowPtr[i + 1]) takeCol(rowIndex.colIdx[p])
                }
            }
        }
    }
    return restrictTo(takenCols, takenRows, colMap, copyCosts = false)
}

/**
 * Restrict this model to [takenCols] × [takenRows] (original indices; [colMap] is their prebuilt
 * old→new column map). The caller guarantees every taken row's whole support is inside [takenCols] —
 * the invariant both consumers establish by construction ([columnNeighborhood]'s BFS, the component
 * labeling). With [copyCosts] the objective restricts too (costs, shift constant, double view);
 * without it costs are zero for a probing caller to overwrite.
 */
internal fun LpModel.restrictTo(
    takenCols: IntArrayList,
    takenRows: IntArrayList,
    colMap: IntArray?,
    copyCosts: Boolean,
    /** Scratch row-index map of length `m`, reusable across calls. A caller that restricts the same model
     *  once per component pays `O(components × m)` in allocation alone otherwise, which on a model with
     *  many small components dwarfs the restriction itself. Cleared for the rows it touches on the way
     *  out, so the buffer comes back all `-1` and the next call cannot see this one's rows. */
    rowMapScratch: IntArray? = null,
): LpNeighborhood {
    val subN = takenCols.size
    val subM = takenRows.size
    val rowMap = rowMapScratch ?: IntArray(m) { -1 }
    for (r in 0 until subM) rowMap[takenRows[r]] = r

    // CSC restricted to the taken columns × taken rows (a taken column's entries in dropped rows
    // vanish with those rows).
    val colPtr = IntArray(subN + 1)
    for (c in 0 until subN) {
        val j = takenCols[c]
        var cnt = 0
        for (k in csc.colPtr[j] until csc.colPtr[j + 1]) if (rowMap[csc.rowIdx[k]] >= 0) cnt++
        colPtr[c + 1] = colPtr[c] + cnt
    }
    val nnz = colPtr[subN]
    val rowIdxOut = IntArray(nnz)
    val colVal = LongArray(nnz)
    for (c in 0 until subN) {
        val j = takenCols[c]
        var w = colPtr[c]
        for (k in csc.colPtr[j] until csc.colPtr[j + 1]) {
            val r = rowMap[csc.rowIdx[k]]
            if (r < 0) continue
            rowIdxOut[w] = r
            colVal[w] = csc.colVal[k]
            w++
        }
    }
    val subVars = subN + subM
    val cost = LongArray(subVars)
    val upperOut = LongArray(subVars)
    val hasUpperOut = BooleanArray(subVars)
    val loShiftOut = LongArray(subN)
    val tagOut = IntArray(subN)
    val clampLo = BooleanArray(subN)
    val clampHi = BooleanArray(subN)
    val continuous = BooleanArray(subN)
    var subObjConstant = 0L
    for (c in 0 until subN) {
        val j = takenCols[c]
        upperOut[c] = upper[j]
        hasUpperOut[c] = hasUpper[j]
        loShiftOut[c] = loShift[j]
        tagOut[c] = tag[j]
        clampLo[c] = probeClampedLo[j]
        clampHi[c] = probeClampedHi[j]
        continuous[c] = colContinuous[j]
        if (copyCosts) {
            cost[c] = this.cost[j]
            subObjConstant = addExact(subObjConstant, mulExact(this.cost[j], loShift[j]))
        }
    }
    val rhsOut = LongArray(subM)
    val flippedOut = LongArray(subM)
    val globalOut = BooleanArray(subM)
    val strictOut = BooleanArray(subM)
    val premisesOut = arrayOfNulls<LpRowPremises?>(subM)
    for (r in 0 until subM) {
        val i = takenRows[r]
        rhsOut[r] = rhs[i]
        flippedOut[r] = flippedRhs[i]
        globalOut[r] = rowGlobal[i]
        strictOut[r] = rowStrict[i]
        premisesOut[r] = rowPremises[i]
        val slack = slackCol(i)
        upperOut[subN + r] = upper[slack]
        hasUpperOut[subN + r] = hasUpper[slack]
    }
    val dv = doubleView?.let { view ->
        // The double view carries its own sparsity (real rows contribute entries the Long core lacks),
        // so it is restricted by its own pattern, not the Long side's layout.
        val dColPtr = IntArray(subN + 1)
        for (c in 0 until subN) {
            val j = takenCols[c]
            var cnt = 0
            for (k in view.colPtr[j] until view.colPtr[j + 1]) if (rowMap[view.rowIdx[k]] >= 0) cnt++
            dColPtr[c + 1] = dColPtr[c] + cnt
        }
        val dRowIdx = IntArray(dColPtr[subN])
        val dColVal = DoubleArray(dColPtr[subN])
        for (c in 0 until subN) {
            val j = takenCols[c]
            var w = dColPtr[c]
            for (k in view.colPtr[j] until view.colPtr[j + 1]) {
                val r = rowMap[view.rowIdx[k]]
                if (r < 0) continue
                dRowIdx[w] = r
                dColVal[w] = view.colVal[k]
                w++
            }
        }
        val dUpper = DoubleArray(subVars)
        val dHasUpper = BooleanArray(subVars)
        val dLoShift = DoubleArray(subN)
        val dCost = DoubleArray(subVars)
        var dObjConstant = 0.0
        for (c in 0 until subN) {
            val j = takenCols[c]
            dUpper[c] = view.upper[j]
            dHasUpper[c] = view.hasUpper[j]
            dLoShift[c] = view.loShift[j]
            if (copyCosts) {
                dCost[c] = view.cost[j]
                dObjConstant += view.cost[j] * view.loShift[j]
            }
        }
        val dRhs = DoubleArray(subM)
        for (r in 0 until subM) {
            val i = takenRows[r]
            dRhs[r] = view.rhs[i]
            dUpper[subN + r] = view.upper[n + i]
            dHasUpper[subN + r] = view.hasUpper[n + i]
        }
        LpDoubleView(
            colPtr = dColPtr, rowIdx = dRowIdx, colVal = dColVal,
            rhs = dRhs, cost = dCost, upper = dUpper, hasUpper = dHasUpper,
            objConstant = dObjConstant, loShift = dLoShift,
        )
    }
    val model = LpModel(
        n = subN, m = subM,
        csc = Csc(colPtr, rowIdxOut, colVal),
        rhs = rhsOut, cost = cost, upper = upperOut, hasUpper = hasUpperOut,
        loShift = loShiftOut, objConstant = subObjConstant, sense = sense, tag = tagOut,
        rowGlobal = globalOut, rowStrict = strictOut, rowPremises = premisesOut,
        flippedRhs = flippedOut, probeClampedLo = clampLo, probeClampedHi = clampHi,
        colContinuous = continuous, doubleView = dv,
    )
    // Hand a borrowed buffer back in the state it arrived in.
    if (rowMapScratch != null) for (r in 0 until subM) rowMap[takenRows[r]] = -1
    return LpNeighborhood(model, colMap, takenRows.toIntArray(), takenCols.toIntArray())
}
