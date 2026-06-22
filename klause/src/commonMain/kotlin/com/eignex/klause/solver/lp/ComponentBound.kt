package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.util.BigRational
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Per-connected-component LP lower bound (#C1, the CP-SAT `AddLPConstraints`-by-component analogue).
 * The structural columns partition into independent blocks — two columns are coupled iff they share a
 * constraint row — and the slack-form objective is a plain sum over columns, so the LP **separates**:
 * its optimum is the sum of the per-block optima plus the isolated (row-free) columns' contributions.
 * Each block is solved and exactly certified over its own small matrix, which fits the per-node size
 * guards and is faster than one monolithic solve, yet yields the *same* bound.
 *
 * Returns `⌈ Σ block optima + Σ isolated-column contributions + objConstant ⌉`, or null to fall back to
 * the monolithic solve when the decomposition is not cleanly applicable — an empty structural row (a
 * slack-only feasibility constraint), an isolated column unbounded below, or any block whose float
 * solve fails to certify. A null only forgoes the decomposition; the caller's monolithic path then
 * produces the (identical) bound, so this is sound by construction.
 *
 * The bounds are summed as exact rationals and ceiled **once** — `⌈Σ⌉ ≠ Σ⌈·⌉`, so per-block ceiling
 * would be wrong; the per-block [ExactBasisCertifier.Certificate.objective] (each built with a zero
 * objective constant) carries the exact rational value.
 */
internal fun componentLowerBoundCeil(model: LpModel, cancellation: Cancellation = Cancellation.Never): Long? {
    val n = model.n
    val m = model.m
    if (n == 0) return null

    // row → the structural columns touching it.
    val rowCols = Array(m) { IntArrayList() }
    for (j in 0 until n) model.forEachInColumn(j) { i, _ -> rowCols[i].add(j) }

    // Union-find over columns that share a row.
    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) {
            parent[r] = parent[parent[r]]
            r = parent[r]
        }
        return r
    }
    val inRow = BooleanArray(n)
    for (i in 0 until m) {
        val cols = rowCols[i]
        if (cols.size == 0) return null // empty structural row → fall back to monolithic
        val root = find(cols[0])
        inRow[cols[0]] = true
        for (k in 1 until cols.size) {
            inRow[cols[k]] = true
            val rk = find(cols[k])
            if (rk != root) parent[rk] = root
        }
    }

    var total = BigRational.ZERO
    // Isolated columns (in no row): contribute min over [0, uⱼ] of cⱼ·z'ⱼ = min(0, cⱼ)·uⱼ (shifted).
    for (j in 0 until n) {
        if (inRow[j]) continue
        val c = model.cost[j]
        if (c < 0L) {
            if (!model.hasUpper[j]) return null // unbounded below → fall back
            total += BigRational.of(c) * BigRational.of(model.upper[j])
        }
    }

    // Group constrained columns by component root (ascending column order within each group).
    val groups = HashMap<Int, IntArrayList>()
    for (j in 0 until n) {
        if (!inRow[j]) continue
        groups.getOrPut(find(j)) { IntArrayList() }.add(j)
    }
    for (cols in groups.values) {
        total += componentObjective(model, cols, cancellation) ?: return null
    }

    total += BigRational.of(model.objConstant)
    return total.ceil().toLongOrNull()
}

/** Exact certified objective of the sub-LP over the component's [cols] (a zero objective constant);
 *  null if the block's float solve does not certify, in which case the caller falls back. */
private fun componentObjective(model: LpModel, cols: IntArrayList, cancellation: Cancellation): BigRational? {
    val n2 = cols.size
    // Rows touched by the component, ascending — so new row indices are monotone in old indices and a
    // column's ascending CSC iteration already produces ascending new rows (no per-column re-sort).
    val touched = BooleanArray(model.m)
    for (jc in 0 until n2) model.forEachInColumn(cols[jc]) { i, _ -> touched[i] = true }
    val rowList = IntArrayList()
    val rowNew = IntArray(model.m) { -1 }
    for (i in 0 until model.m) {
        if (touched[i]) {
            rowNew[i] = rowList.size
            rowList.add(i)
        }
    }
    val rows = rowList.toIntArray()
    val m2 = rows.size
    val numVars2 = n2 + m2

    // Sub-CSC: column jc holds the original column cols[jc] entries with rows remapped (ascending).
    val colPtr = IntArray(n2 + 1)
    val rowIdx = IntArrayList()
    val colVal = LongArrayList()
    for (jc in 0 until n2) {
        colPtr[jc] = rowIdx.size
        model.forEachInColumn(cols[jc]) { i, v ->
            rowIdx.add(rowNew[i])
            colVal.add(v)
        }
    }
    colPtr[n2] = rowIdx.size

    val rhs = LongArray(m2) { model.rhs[rows[it]] }
    val cost = LongArray(numVars2) { if (it < n2) model.cost[cols[it]] else 0L }
    val upper = LongArray(numVars2) {
        if (it < n2) model.upper[cols[it]] else model.upper[model.slackCol(rows[it - n2])]
    }
    val hasUpper = BooleanArray(numVars2) {
        if (it < n2) model.hasUpper[cols[it]] else model.hasUpper[model.slackCol(rows[it - n2])]
    }
    val loShift = LongArray(n2) { model.loShift[cols[it]] }
    val tag = IntArray(n2) { model.tag[cols[it]] }

    val sub = LpModel(
        n = n2,
        m = m2,
        csc = Csc(colPtr, rowIdx.toIntArray(), colVal.toLongArray()),
        rhs = rhs,
        cost = cost,
        upper = upper,
        hasUpper = hasUpper,
        loShift = loShift,
        objConstant = 0L, // the global constant is added once by the caller
        sense = model.sense,
        tag = tag,
    )
    val result = RevisedSimplex(sub, cancellation).solve() ?: return null
    return ExactBasisCertifier.certify(sub, result.basis)?.objective
}
