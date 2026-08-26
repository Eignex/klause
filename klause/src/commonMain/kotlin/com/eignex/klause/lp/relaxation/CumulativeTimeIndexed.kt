package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Time-indexed `x_{i,t}` relaxation of one scheduling [view] over the bounded horizon
 * `[T0, T1)`. For each task a binary `x_{i,t} ∈ [0,1]` per declared-feasible start `t`
 * (pinned to 0 when `t` left the live start domain — layout stable across nodes for warm
 * starts), with `Σ_t x_{i,t} = 1` (starts once), the start channel `Σ_t t·x_{i,t} = startᵢ`
 * (ties to the integer column), and per-time-point resource rows
 * `Σ_i Σ_{t: t≤tt<t+durᵢ} resᵢ·x_{i,t} ≤ capacity`. Every integer schedule satisfies all three,
 * so the rows are globally valid; the resource ceiling uses the declared **max** capacity and
 * the **min** demand, so it is a sound relaxation. Columns are O(n·H) — hard-gated on
 * [CpToLpRelaxation.MAX_TI_HORIZON] and [CpToLpRelaxation.MAX_TI_COLS]; above either the model is
 * skipped (only loosens). Column and row emission order is warm-start load-bearing — the layout
 * must be reproducible across nodes, so nothing here may reorder by live state.
 *
 * ## No separate makespan row
 * There is deliberately no disaggregated makespan row here. The makespan links through the
 * start channel and the model's own `M ≥ startᵢ + durᵢ` `Linear`s, so the LP makespan is
 * `Σ_t t·x_{i,t} + durᵢ` — the *expected* completion under a fractional `x`. The disaggregated
 * disaggregated `M ≥ (t+durᵢ)·x_{i,t}` rows (and the completion-indicator step variant)
 * cannot tighten that: any makespan lower bound linear in one task's `x` is dominated by the
 * expected-completion value, which `M ≥ startᵢ + durᵢ` already attains exactly (verified — the
 * disaggregated rows raised the bound on 0 of 2623 structured instances). The only makespan
 * lever is the *cross-task* resource coupling above, which already lifts the bound past the
 * energetic windowed row on multi-capacity profiles. So the model is not redundant with the
 * energetic makespan row, but the disaggregated strengthening would be.
 */
internal fun RelaxationBuilder.buildCumulativeTimeIndexed(view: SchedulingView) {
    val n = view.starts.size
    val est = LongArray(n) { declaredDomain(view.starts[it]).min }
    val lst = LongArray(n) { declaredDomain(view.starts[it]).max }
    var t0 = Long.MAX_VALUE
    var t1 = Long.MIN_VALUE
    var cols = 0L
    for (i in 0 until n) {
        if (lst[i] < est[i]) return // empty declared start domain — leave to propagation
        if (est[i] < t0) t0 = est[i]
        val end = lst[i] + view.durations[i]
        if (end > t1) t1 = end
        cols += lst[i] - est[i] + 1
    }
    val horizon = t1 - t0
    if (horizon <= 0 || horizon > CpToLpRelaxation.MAX_TI_HORIZON || cols > CpToLpRelaxation.MAX_TI_COLS) return

    // Per-task start-time columns, indexed by (t - est_i); assignment + start channel rows.
    val taskCols = Array(n) { IntArray((lst[it] - est[it] + 1).toInt()) }
    for (i in 0 until n) {
        val live = liveDomain(view.starts[i])
        val assignCols = IntArray(taskCols[i].size)
        val chanCols = IntArray(taskCols[i].size + 1)
        val chanVals = LongArray(taskCols[i].size + 1)
        for (k in taskCols[i].indices) {
            val t = est[i] + k
            val col = auxColumn(0L, if (live.contains(t)) 1L else 0L)
            taskCols[i][k] = col
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
