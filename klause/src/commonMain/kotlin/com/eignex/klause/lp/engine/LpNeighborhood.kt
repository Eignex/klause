package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.pollStop
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact

// The rows a breadth-first walk of the column/row graph reaches from the seed columns before the row cap, plus every
// structural column they touch. Only whole rows are dropped, so the sub-model is a relaxation: any bound proved on it
// (probe optimum, dual bound, Farkas refutation) holds on the full model, at a factorization cost bounded by the cap.
// Bounds, shifts, probe flags, tags, strictness, global validity, premises and the double view carry over. The same
// [LpModel.restrictTo] carves connected components, where no dropped row shares a column with a kept one, so the
// restriction is exact ([ComponentLpSolver]).
internal class LpNeighborhood(
    val model: LpModel,
    // -1 outside; null when the caller tracks columns via [cols] alone, since full-length maps cost n × parts.
    val colMap: IntArray?,
    // Original indices: sub-model row r is original row rows(r), and likewise for cols.
    val rows: IntArray,
    val cols: IntArray,
) {
    fun colOf(j: Int): Int = checkNotNull(colMap) { "this restriction tracks columns via cols only" }[j]
}

// Built once in O(nnz) and shared across [columnNeighborhood] walks. Covers the exact state's nonzeros when present,
// else the union of the Long core's and the double view's sparsity: the patterns differ on mixed models, and the
// walk's whole-row soundness needs every entry of either.
internal class LpRowIndex(val rowPtr: IntArray, val colIdx: IntArray)

internal fun LpModel.rowIndex(projection: LpProjectionMeter? = null): LpRowIndex {
    // Deduplicate per column across the two patterns (rows ascending within each), then bucket by row.
    projection?.reserve(m.toLong() + 1L, (m.toLong() + 1L) * 4L, matrix = true)
    val rowPtr = IntArray(m + 1)
    for (column in 0 until n) {
        projection?.reserve(1L, 0L, matrix = true)
        forEachColumnRow(column) { i ->
            projection?.reserve(1L, 0L, matrix = true)
            rowPtr[i + 1]++
        }
    }
    projection?.reserve(m.toLong(), 0L, matrix = true)
    for (i in 0 until m) rowPtr[i + 1] += rowPtr[i]
    projection?.reserve(rowPtr[m].toLong(), (rowPtr[m].toLong() + m + 1L) * 4L, matrix = true)
    val colIdx = IntArray(rowPtr[m])
    val cursor = rowPtr.copyOf()
    for (column in 0 until n) {
        projection?.poll()
        forEachColumnRow(column) { i -> colIdx[cursor[i]++] = column }
    }
    return LpRowIndex(rowPtr, colIdx)
}

// Each union-sparsity row once, ascending.
internal inline fun LpModel.forEachColumnRow(j: Int, action: (i: Int) -> Unit) {
    exactState?.let { state ->
        for (entry in state.model.columnEntries(j)) if (!entry.number.value.isZero) action(entry.row)
        return
    }
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

// Each union entry once, columns outer.
internal inline fun LpModel.forEachUnionEntry(action: (j: Int, i: Int) -> Unit) {
    for (j in 0 until n) forEachColumnRow(j) { i -> action(j, i) }
}

// A row past the cap is dropped whole, never truncated. Costs are zero; a probing caller installs its objective via
// [LpModel.withSingleColumnObjective].
internal fun LpModel.columnNeighborhood(seeds: IntArray, maxRows: Int, rowIndex: LpRowIndex): LpNeighborhood {
    val colMap = IntArray(n) { -1 }
    val takenRows = IntArrayList()
    val takenCols = IntArrayList()
    val rowSeen = IntHashSet()
    val colQueue = IntArrayDeque()
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

// The caller guarantees every taken row's whole support is inside [takenCols]. Without [copyCosts] costs are zero
// for a probing caller to overwrite.
internal fun LpModel.restrictTo(
    takenCols: IntArrayList,
    takenRows: IntArrayList,
    colMap: IntArray?,
    copyCosts: Boolean,
    // Reusable across calls, since per-component allocation alone costs O(components × m). The rows it touches are
    // reset to -1 on the way out.
    rowMapScratch: IntArray? = null,
): LpNeighborhood {
    val subN = takenCols.size
    val subM = takenRows.size
    val rowMap = rowMapScratch ?: IntArray(m) { -1 }
    for (r in 0 until subM) rowMap[takenRows[r]] = r
    exactState?.let { state ->
        try {
            return state.restrictTo(takenCols, takenRows, colMap, copyCosts, rowMap)
        } finally {
            if (rowMapScratch != null) for (r in 0 until subM) rowMap[takenRows[r]] = -1
        }
    }

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
        if (copyCosts) cost[subN + r] = this.cost[slack]
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
            if (copyCosts) dCost[subN + r] = view.cost[n + i]
            dUpper[subN + r] = view.upper[n + i]
            dHasUpper[subN + r] = view.hasUpper[n + i]
        }
        LpDoubleView(
            colPtr = dColPtr, rowIdx = dRowIdx, colVal = dColVal,
            rhs = dRhs, cost = dCost, upper = dUpper, hasUpper = dHasUpper,
            objConstant = dObjConstant, loShift = dLoShift, inexactCoefficients = view.inexactCoefficients,
            shifts = restrictedShifts(view.shifts, takenRows, takenCols),
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

private fun LpExactState.restrictTo(
    columns: IntArrayList,
    rows: IntArrayList,
    colMap: IntArray?,
    copyCosts: Boolean,
    rowMap: IntArray,
): LpNeighborhood {
    val source = model
    val indices = columns.toIntArray() + IntArray(rows.size) { source.n + rows[it] }
    val zero = ExactLpNumber.of(0L)
    var ordered = true
    for (index in 1 until rows.size) {
        if (rows[index - 1] >= rows[index]) {
            ordered = false
            break
        }
    }
    val restricted = ExactLpModel(
        matrix = List(columns.size) { column ->
            val entries = source.columnEntries(columns[column]).mapNotNull { entry ->
                rowMap[entry.row].takeIf { it >= 0 }?.let { ExactLpEntry(it, entry.number) }
            }
            if (ordered) entries else entries.sortedBy { it.row }
        },
        rhs = List(rows.size) { source.rhs(rows[it]) },
        columns = indices.map { source.column(it) },
        rows = List(rows.size) { source.row(rows[it]) },
        // Parts certify contributions in raw minimized units; the parent applies its affine objective once.
        objective = ExactLpObjective(indices.map { if (copyCosts) source.objective.cost(it) else zero }),
    )
    val identities = LpScopedRows(
        List(rows.size) { LpRowIdentity(it.toLong(), null, active = this.rows.row(rows[it]).active) },
        rows.size - 1L,
    )
    val working = requireNotNull(LpExactState(restricted, rows = identities).ownerWorkingModel()) {
        "restriction of a projected exact model must project"
    }
    return LpNeighborhood(working, colMap, rows.toIntArray(), columns.toIntArray())
}

internal fun LpNeighborhood.rebindExact(
    state: LpExactState,
    token: ExecutionControl,
    projection: LpProjectionMeter? = null,
): LpNeighborhood? {
    if (token()) return null
    val previous = model.exactState ?: return null
    val source = state.model
    fun parentColumn(local: Int): Int = if (local < cols.size) cols[local] else source.n + rows[local - cols.size]
    projection?.reserve(model.numVars.toLong() + rows.size, 0L, matrix = false)
    var changed = false
    var boundsOnly = true
    for (local in 0 until model.numVars) {
        if (token.pollStop()) return null
        val original = parentColumn(local)
        val column = source.column(original)
        val old = previous.model.column(local)
        val declared = previous.baseModel.column(local)
        val cost = source.objective.cost(original)
        if (column.origin != old.origin) return null
        if (column != old || cost != previous.model.objective.cost(local)) changed = true
        if (column.origin != declared.origin || column.integral != declared.integral || column.tag != declared.tag ||
            cost != previous.baseModel.objective.cost(local)
        ) boundsOnly = false
    }
    // Global trail revisions do not invalidate a block whose complete numerical and premise authority is unchanged.
    for (local in rows.indices) {
        if (token.pollStop()) return null
        val original = rows[local]
        val rhs = source.rhs(original)
        val metadata = source.row(original)
        if (rhs != previous.model.rhs(local) || metadata != previous.model.row(local)) changed = true
        if (rhs != previous.baseModel.rhs(local) || metadata != previous.baseModel.row(local)) boundsOnly = false
        if (state.rows.row(original).active != previous.rows.row(local).active) {
            boundsOnly = false
            changed = true
        }
    }
    if (token()) return null
    if (!changed) return this
    val dimensions = model.numVars.toLong() + rows.size
    projection?.reserve(dimensions * 4L + 1L, (dimensions + 1L) * 128L, matrix = false)
    val identities = LpScopedRows(
        List(rows.size) { LpRowIdentity(it.toLong(), null, active = state.rows.row(rows[it]).active) },
        rows.size - 1L,
    )
    val columns = List(model.numVars) { source.column(parentColumn(it)) }
    // Checked origins and unchanged metadata let this owned column list preserve the other immutable witnesses.
    val rebound = if (boundsOnly) previous.baseModel.withBoundColumns(columns) else previous.baseModel.copy(
        rhs = rows.map { source.rhs(it) },
        columns = columns,
        rows = rows.map { source.row(it) },
        objective = ExactLpObjective(List(model.numVars) { source.objective.cost(parentColumn(it)) }),
    )
    val next = LpExactState(
        rebound,
        matrixRevision = previous.matrixRevision,
        boundRevision = state.boundRevision,
        objectiveRevision = state.objectiveRevision,
        popRevision = state.popRevision,
        rows = identities,
        rowRevision = state.rowRevision,
        previous = previous,
    )
    if (!previous.sameMatrix(next)) return null
    next.inheritProjection(previous)
    next.inheritScalars(previous)
    val working = next.ownerWorkingModel(projection ?: LpProjectionMeter(cancellation = token)) ?: return null
    if (token()) return null
    return LpNeighborhood(working, colMap, rows, cols)
}

// A row keeps its parent's exact right-hand side: the shifts of every column it reads are already folded into it.
private fun restrictedShifts(
    parent: Lazy<LpExactShifts>,
    rows: IntArrayList,
    columns: IntArrayList,
): Lazy<LpExactShifts> {
    if (parent.isInitialized() && parent.value === LpExactShifts.NONE) return parent
    val takenRows = rows.toIntArray()
    val takenColumns = columns.toIntArray()
    return lazy { parent.value.restrict(takenRows, takenColumns) }
}
