package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

// Time-indexed relaxation: per task a column x_{i,t} per root-feasible start t (pinned to 0 once t
// leaves the live domain), Σ_t x_{i,t} = 1, the channel Σ_t t·x_{i,t} = startᵢ, and per-time resource
// rows against the declared maximum capacity and minimum demands. Every integer schedule satisfies them.
// Emission order must not depend on live state: the layout has to be reproducible across nodes.
//
// Pritsker, Watters and Wolfe, "Multiproject scheduling with limited resources: a zero-one programming
// approach", Management Science 16 (1969), https://doi.org/10.1287/mnsc.16.1.93.
//
// A view with an open-sided start is declined: Σ_t x_{i,t} = 1 would pin the start inside the root box,
// a search restriction, and exclude starts the model admits.
//
// No disaggregated makespan row: through the start channel and the model's M ≥ startᵢ + durᵢ rows the LP
// makespan is already the expected completion, which dominates any bound linear in one task's x. The
// cross-task resource rows are what lift the bound.
internal fun RelaxationBuilder.buildCumulativeTimeIndexed(view: SchedulingView) {
    if (!statesBothBounds(view.starts)) return
    val n = view.starts.size
    val est = LongArray(n) { rootDomain(view.starts[it]).min }
    val lst = LongArray(n) { rootDomain(view.starts[it]).max }
    var t0 = Long.MAX_VALUE
    var t1 = Long.MIN_VALUE
    var cols = 0L
    for (i in 0 until n) {
        if (lst[i] < est[i]) return // empty root start box — leave to propagation
        if (est[i] < t0) t0 = est[i]
        val end = lst[i] + view.durations[i]
        if (end > t1) t1 = end
        cols += lst[i] - est[i] + 1
    }
    val horizon = t1 - t0
    if (horizon <= 0 || horizon > CpToLpRelaxation.MAX_TI_HORIZON || cols > CpToLpRelaxation.MAX_TI_COLS) return

    // Per-task start-time columns, indexed by t − estᵢ.
    val taskCols = Array(n) { IntArray((lst[it] - est[it] + 1).toInt()) }
    for (i in 0 until n) {
        val live = liveDomain(view.starts[i])
        val assignCols = taskCols[i]
        val chanCols = IntArray(taskCols[i].size + 1)
        val chanVals = LongArray(taskCols[i].size + 1)
        for (k in taskCols[i].indices) {
            val t = est[i] + k
            val col = auxColumn(0L, if (live.contains(t)) 1L else 0L)
            assignCols[k] = col
            chanCols[k] = col
            chanVals[k] = t
        }
        row(assignCols, LongArray(assignCols.size) { 1L }, LinearOp.EQ, 1L)
        chanCols[taskCols[i].size] = intColumn(view.starts[i])
        chanVals[taskCols[i].size] = -1L
        row(chanCols, chanVals, LinearOp.EQ, 0L) // Σ t·x − startᵢ = 0
    }

    // Per-time-point resource rows: Σ_i Σ_{t ≤ tt < t+durᵢ} resᵢ·x_{i,t} ≤ capacity.
    val rowCols = IntArrayList()
    val rowVals = LongArrayList()
    for (tt in t0 until t1) {
        rowCols.clear()
        rowVals.clear()
        for (i in 0 until n) {
            val d = view.durations[i]
            val r = view.resources[i]
            if (d <= 0 || r <= 0L) continue
            val lo = maxOf(est[i], tt - d + 1)
            val hi = minOf(lst[i], tt)
            for (t in lo..hi) {
                rowCols.add(taskCols[i][(t - est[i]).toInt()])
                rowVals.add(r)
            }
        }
        if (!rowCols.isEmpty()) {
            row(rowCols.toIntArray(), rowVals.toLongArray(), LinearOp.LE, view.capacity)
        }
    }
}
