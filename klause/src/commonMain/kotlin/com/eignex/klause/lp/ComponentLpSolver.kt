package com.eignex.klause.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.util.IntArrayList

/**
 * Component-decomposed LP solve: when the structural columns split into independent blocks (two
 * columns are coupled iff they share a row of the union sparsity), each block is its own [LpModel]
 * solved by its own engine, and the block results stitch back into one full-model [FloatLpResult].
 * Unlike a neighborhood restriction this is **exact**, not a relaxation — no dropped row shares a
 * variable with a kept one — so the stitched optimum, primal point, and dual vector are precisely
 * what the monolithic solve would produce, while each block's basis factorization costs a fraction
 * of the monolithic one. An infeasible block certifies the whole model infeasible: its Farkas ray
 * scattered to full row length (zeros on the other blocks' rows) is a valid full-model ray.
 *
 * Structural columns in no row ([isolated]) need no solve at all: each rides to whichever of its
 * bounds its cost prefers; an isolated column that prefers an infinite bound makes the LP unbounded,
 * reported as a failed solve exactly like the monolithic engine would.
 *
 * The warm-start handle is not split across blocks — a decomposed solve cold-starts each block
 * (small by construction, which is the point); the warm contract permits this (it changes only the
 * pivot path, never the result).
 */
internal class ComponentLpSolver(
    private val model: LpModel,
    private val parts: List<LpNeighborhood>,
    private val solvers: List<LpSolver>,
    private val isolated: IntArray,
) : LpSolver {
    override var infeasibleRay: DoubleArray? = null
        private set

    override fun solve(warm: Basis?): FloatLpResult? = stitch { s -> s.solve(null) }

    override fun solvePrimal(warm: Basis?): FloatLpResult? = stitch { s -> s.solvePrimal(null) }

    private inline fun stitch(op: (LpSolver) -> FloatLpResult?): FloatLpResult? {
        infeasibleRay = null
        var objective = 0.0
        val primal = DoubleArray(model.n)
        val duals = DoubleArray(model.m)
        val status = Array(model.numVars) { VarStatus.AT_LOWER }
        val basicVars = IntArray(model.m)
        var basicAt = 0
        var pivots = 0
        var maxFill = 0.0
        var maxDensity = 0.0
        for (j in isolated) {
            val c = model.costD(j)
            var shifted = 0.0
            if (c < 0.0) {
                if (!model.hasFiniteUpper(j)) return null // unbounded objective, as the engine reports
                shifted = model.upperD(j)
                status[j] = VarStatus.AT_UPPER
            }
            primal[j] = shifted + model.loShiftD(j)
            objective += c * shifted + c * model.loShiftD(j)
        }
        for (k in parts.indices) {
            val part = parts[k]
            val r = op(solvers[k]) ?: run {
                // A dual-unbounded block is a candidate infeasibility of the whole model: its float
                // ray extends with zeros on the other blocks' rows.
                solvers[k].infeasibleRay?.let { ray ->
                    val full = DoubleArray(model.m)
                    for (i in ray.indices) full[part.rows[i]] = ray[i]
                    infeasibleRay = full
                }
                return null
            }
            objective += r.objective
            val sub = part.model
            for (c in 0 until sub.n) {
                primal[part.cols[c]] = r.primal[c]
                status[part.cols[c]] = r.basis.status[c]
            }
            for (i in 0 until sub.m) {
                duals[part.rows[i]] = r.duals[i]
                status[model.slackCol(part.rows[i])] = r.basis.status[sub.n + i]
            }
            for (b in r.basis.basicVars) {
                basicVars[basicAt++] = if (b < sub.n) part.cols[b] else model.slackCol(part.rows[b - sub.n])
            }
            pivots += r.pivots
            if (r.luMaxFill > maxFill) maxFill = r.luMaxFill
            if (r.luMaxDensity > maxDensity) maxDensity = r.luMaxDensity
        }
        return FloatLpResult(
            basis = Basis(basicVars, status),
            objective = objective,
            duals = duals,
            primal = primal,
            pivots = pivots,
            luMaxFill = maxFill,
            luMaxDensity = maxDensity,
        )
    }
}

/**
 * Decompose [model] into its column components and wrap them as a [ComponentLpSolver], or null when
 * decomposition does not apply: fewer than two row-coupled blocks, or an empty structural row (its
 * feasibility is not attached to any column, so it belongs to no block — the monolithic engine
 * handles it). [engine] builds each block's solver — the same monolithic selection [newLpSolver]
 * uses, so a block solves exactly as the whole model would.
 */
internal fun componentLpSolverOrNull(
    model: LpModel,
    cancellation: Cancellation,
    engine: (LpModel, Cancellation) -> LpSolver,
): LpSolver? {
    val n = model.n
    val m = model.m
    if (n == 0 || m < 2) return null
    val rowIndex = model.rowIndex()
    // Union-find over columns sharing a row.
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
        val from = rowIndex.rowPtr[i]
        val to = rowIndex.rowPtr[i + 1]
        if (from == to) return null // empty structural row → monolithic
        val root = find(rowIndex.colIdx[from])
        inRow[rowIndex.colIdx[from]] = true
        for (p in from + 1 until to) {
            val j = rowIndex.colIdx[p]
            inRow[j] = true
            val rj = find(j)
            if (rj != root) parent[rj] = root
        }
    }
    // Order components by first-seen root; a model with a single row-coupled block stays monolithic.
    val blockOf = IntArray(n) { -1 }
    var blocks = 0
    for (j in 0 until n) {
        if (!inRow[j]) continue
        val root = find(j)
        if (blockOf[root] < 0) blockOf[root] = blocks++
    }
    if (blocks < 2) return null

    val blockCols = Array(blocks) { IntArrayList() }
    for (j in 0 until n) if (inRow[j]) blockCols[blockOf[find(j)]].add(j)
    val blockRows = Array(blocks) { IntArrayList() }
    for (i in 0 until m) blockRows[blockOf[find(rowIndex.colIdx[rowIndex.rowPtr[i]])]].add(i)
    val isolated = IntArrayList()
    for (j in 0 until n) if (!inRow[j]) isolated.add(j)

    val parts = ArrayList<LpNeighborhood>(blocks)
    for (b in 0 until blocks) {
        parts.add(model.restrictTo(blockCols[b], blockRows[b], colMap = null, copyCosts = true))
    }
    val solvers = parts.map { engine(it.model, cancellation) }
    return ComponentLpSolver(model, parts, solvers, isolated.toIntArray())
}
