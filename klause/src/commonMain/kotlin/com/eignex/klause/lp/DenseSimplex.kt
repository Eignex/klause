package com.eignex.klause.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.koblas.DenseMatrix
import com.eignex.koblas.LuDecomposition
import com.eignex.koblas.koblas
import kotlin.math.abs

/**
 * A dense bounded-variable simplex — an alternative [LpSolver] for models whose basis fills in to dense,
 * where the sparse [RevisedSimplex]'s `O(nnz)` machinery buys nothing. It solves the same normalized LP
 * (`A z = rhs`, `0 ≤ z ≤ upper`, minimize `cᵀz`) via a textbook two-phase primal simplex, maintaining the
 * basis with koblas's dense LU ([koblas]) for FTRAN/BTRAN. Bland's rule guarantees termination.
 *
 * It reads the model through the double accessors, so it serves the LP-only-continuous (real) models
 * too. Selected behind [newLpSolver] by density; the sparse engine remains the default and owns cuts.
 * Like [RevisedSimplex] it is a float heuristic whose result is certified exactly downstream — a null
 * return (non-convergence / unbounded) simply keeps the node.
 */
internal class DenseSimplex(private val model: LpModel, private val cancellation: Cancellation = Cancellation.Never) :
    LpSolver {
    private val m = model.m
    private val n = model.n
    private val numVars = model.numVars
    private val total = numVars + m // originals + one artificial per row

    // Dense column of A_full for every variable: structural (CSC), slack (unit), artificial (±unit).
    private val artSign = DoubleArray(m) { if (model.rhsD(it) >= 0.0) 1.0 else -1.0 }

    override var infeasibleRay: DoubleArray? = null
        private set

    private fun column(j: Int): DoubleArray {
        val out = DoubleArray(m)
        when {
            j < n -> model.forEachInColumnD(j) { i, v -> out[i] = v }

            j < numVars -> out[j - n] = 1.0

            // slack
            else -> out[j - numVars] = artSign[j - numVars] // artificial
        }
        return out
    }

    /** Upper bound of variable [j]; `+∞` (returned as [Double.MAX_VALUE]) for an unbounded inequality
     *  slack. An artificial is fixed at `[0, 0]` outside phase 1. */
    private fun upperOf(j: Int, phase1: Boolean): Double = when {
        j < numVars -> if (model.hasFiniteUpper(j)) model.upperD(j) else Double.MAX_VALUE
        phase1 -> Double.MAX_VALUE
        else -> 0.0
    }

    private fun costOf(j: Int, phase1: Boolean): Double =
        if (phase1) (if (j >= numVars) 1.0 else 0.0) else (if (j < numVars) model.costD(j) else 0.0)

    override fun solve(warm: Basis?): FloatLpResult? = run()

    override fun solvePrimal(warm: Basis?): FloatLpResult? = run()

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun run(): FloatLpResult? {
        val status = Array(total) { VarStatus.AT_LOWER }
        val basicVar = IntArray(m) { numVars + it } // start: artificials basic
        for (i in 0 until m) status[numVars + i] = VarStatus.BASIC
        val maxIter = 50 * (m + total) + 200

        var phase1 = true
        var iter = 0
        while (true) {
            if (iter++ > maxIter || cancellation()) return null

            // Basis matrix B (m×m), row-major, column t = basicVar[t]; factor with koblas dense LU.
            val bData = DoubleArray(m * m)
            for (t in 0 until m) {
                val col = column(basicVar[t])
                for (i in 0 until m) bData[i * m + t] = col[i]
            }
            val lu = koblas.factor(DenseMatrix.wrap(m, m, bData))
            if (lu.singular) return null

            // rhsAdj = rhs − Σ_{nonbasic at upper} A_j·u_j ; x_B = B⁻¹ rhsAdj.
            val rhsAdj = DoubleArray(m) { model.rhsD(it) }
            for (j in 0 until total) {
                if (status[j] == VarStatus.AT_UPPER) {
                    val u = upperOf(j, phase1)
                    val col = column(j)
                    for (i in 0 until m) rhsAdj[i] -= col[i] * u
                }
            }
            val xB = koblas.solve(lu, rhsAdj)

            // Duals y = B⁻ᵀ c_B, reduced costs d_j = c_j − yᵀA_j.
            val cB = DoubleArray(m) { costOf(basicVar[it], phase1) }
            val y = koblas.solve(lu, cB, transpose = true)

            // Entering variable by Bland's rule: smallest index that improves.
            var q = -1
            var dir = 0
            for (j in 0 until total) {
                if (status[j] == VarStatus.BASIC) continue
                if (!phase1 && j >= numVars) continue // artificials fixed at 0 in phase 2
                var d = costOf(j, phase1)
                val col = column(j)
                for (i in 0 until m) d -= y[i] * col[i]
                if (status[j] == VarStatus.AT_LOWER && d < -TOL) {
                    q = j
                    dir = 1
                    break
                }
                if (status[j] == VarStatus.AT_UPPER && d > TOL) {
                    q = j
                    dir = -1
                    break
                }
            }

            if (q == -1) {
                if (phase1) {
                    // Phase-1 optimum: infeasible iff any artificial carries value.
                    var infeas = 0.0
                    for (t in 0 until m) if (basicVar[t] >= numVars) infeas += abs(xB[t])
                    if (infeas > FEAS_TOL) {
                        infeasibleRay = y.copyOf() // phase-1 duals are a Farkas direction
                        return null
                    }
                    phase1 = false
                    iter = 0
                    continue
                }
                return optimal(basicVar, status, xB, lu)
            }

            // Ratio test (bounded-variable): α = B⁻¹ A_q; how far q can move before something hits a bound.
            val alpha = koblas.solve(lu, column(q))
            var tMax = upperOf(q, phase1) // q's own opposite bound (bound flip)
            var leave = -1
            var leaveToUpper = false
            for (i in 0 until m) {
                val a = dir * alpha[i]
                val bi = basicVar[i]
                if (a > TOL) { // x_Bi decreasing toward 0
                    val t = xB[i] / a
                    if (t < tMax - TOL || (t < tMax + TOL && (leave == -1 || bi < basicVar[leave]))) {
                        tMax = minOf(tMax, t)
                        leave = i
                        leaveToUpper = false
                    }
                } else if (a < -TOL) { // x_Bi increasing toward its upper
                    val u = upperOf(bi, phase1)
                    if (u >= Double.MAX_VALUE) continue
                    val t = (xB[i] - u) / a
                    if (t < tMax - TOL || (t < tMax + TOL && (leave == -1 || bi < basicVar[leave]))) {
                        tMax = minOf(tMax, t)
                        leave = i
                        leaveToUpper = true
                    }
                }
            }
            if (tMax >= Double.MAX_VALUE) return null // unbounded (no finite step, no finite q bound)

            if (leave == -1) {
                // Bound flip: q crosses to its other bound, basis unchanged.
                status[q] = if (dir == 1) VarStatus.AT_UPPER else VarStatus.AT_LOWER
            } else {
                val leaving = basicVar[leave]
                status[leaving] = if (leaveToUpper) VarStatus.AT_UPPER else VarStatus.AT_LOWER
                basicVar[leave] = q
                status[q] = VarStatus.BASIC
            }
        }
    }

    private fun optimal(
        basicVar: IntArray,
        status: Array<VarStatus>,
        xB: DoubleArray,
        lu: LuDecomposition,
    ): FloatLpResult {
        // Objective in original coordinates: Σ c_j·value(j) + objConstant.
        var obj = model.objConstantD
        for (j in 0 until numVars) if (status[j] == VarStatus.AT_UPPER) obj += model.costD(j) * model.upperD(j)
        for (t in 0 until m) {
            val bj = basicVar[t]
            if (bj < numVars) obj += model.costD(bj) * xB[t]
        }
        // Primal (per structural variable, unshifted).
        val primal = DoubleArray(n)
        for (j in 0 until n) {
            primal[j] = model.loShiftD(
                j,
            ) + if (status[j] == VarStatus.AT_UPPER) model.upperD(j) else 0.0
        }
        for (t in 0 until m) {
            val bj = basicVar[t]
            if (bj < n) primal[bj] = model.loShiftD(bj) + xB[t]
        }
        // Duals over the m rows, from the final basis.
        val cB = DoubleArray(m) { if (basicVar[it] < numVars) model.costD(basicVar[it]) else 0.0 }
        val duals = koblas.solve(lu, cB, transpose = true)
        // Report a Basis over the original numVars: a still-basic artificial (a redundant row) is shown as
        // that row's slack, keeping the Basis well-formed (the authoritative outputs are duals/primal).
        val reported = IntArray(m) { t -> basicVar[t].let { if (it >= numVars) n + (it - numVars) else it } }
        return FloatLpResult(Basis(reported, Array(numVars) { status[it] }), obj, duals, primal)
    }

    companion object {
        private const val TOL = 1e-9
        private const val FEAS_TOL = 1e-7
    }
}
