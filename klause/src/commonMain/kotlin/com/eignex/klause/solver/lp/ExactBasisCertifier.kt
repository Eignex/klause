package com.eignex.klause.solver.lp

import com.eignex.klause.util.BigInt
import com.eignex.klause.util.BigRational

/**
 * Exact lower bound on the minimized objective `cᵀz`, certified from a (float-found) [Basis] using
 * exact rationals — the fallback for when the `Long` fraction-free path ([DualSimplex]) overflows,
 * which happens precisely on the large-determinant bases of big models.
 *
 * It solves the dual system `M y = c_B` exactly (`M[t][i] = A_full[i][basicVar[t]]`) by fraction-free
 * Bareiss elimination (the O(m³) work, pure-integer / no gcd) plus a cheap rational back-substitution.
 * The Lagrangian `L(y) = y·rhs + Σ_j min_{[0,ub_j]} d_j·z_j` is a valid lower
 * bound on the optimum for *any* `y` (the slack-form constraints are equalities), so no dual-
 * feasibility check is needed — and computed exactly it is rigorously sound. Returns null if the
 * basis is singular (a bad float basis) or a negative reduced cost meets an infinite upper bound
 * (unbounded Lagrangian); the caller then simply keeps the node.
 */
internal object ExactBasisCertifier {

    /** Exact ceil of the objective lower bound `⌈L(y)⌉`, a valid integer lower bound, or null. */
    fun lowerBoundCeil(model: LpModel, basis: Basis): Long? = lagrangian(model, basis)?.ceil()?.toLongOrNull()

    private fun lagrangian(model: LpModel, basis: Basis): BigRational? {
        val m = model.m
        val basic = basis.basicVars
        val y = solveDual(model, basic) ?: return null

        var l = BigRational.ZERO
        for (i in 0 until m) l += y[i] * BigRational.of(model.rhs[i])
        val nonBasic = BooleanArray(model.numVars) { true }
        for (t in 0 until m) nonBasic[basic[t]] = false
        for (j in 0 until model.numVars) {
            if (!nonBasic[j]) continue
            var dot = BigRational.ZERO
            for (i in 0 until m) {
                val a = fullEntry(model, i, j)
                if (a != 0L) dot += y[i] * BigRational.of(a)
            }
            val dj = BigRational.of(model.cost[j]) - dot // reduced cost c_j − yᵀA_j
            if (dj.signum() < 0) {
                if (!model.hasUpper[j]) return null // unbounded below
                l += dj * BigRational.of(model.upper[j])
            }
        }
        // L(y) is the lower-bound-shifted objective; re-add the constant the shift folded out
        // (`c·lo`), exactly as DualSimplex does, so the bound is on the true objective.
        return l + BigRational.of(model.objConstant)
    }

    /** Exact solve of `M y = c_B` with `M[t][i] = A_full[i][basicVar[t]]`: fraction-free Bareiss
     *  forward elimination (integer, no gcd) then rational back-substitution. */
    private fun solveDual(model: LpModel, basic: IntArray): Array<BigRational>? {
        val m = model.m
        // Augmented [M | c_B] in BigInt; the O(m³) elimination below is fraction-free (Bareiss):
        // pure-integer, no per-op gcd, with magnitudes bounded by a single determinant.
        val a = Array(m) { t ->
            val col = basic[t]
            Array(m + 1) { j -> BigInt.of(if (j < m) fullEntry(model, j, col) else model.cost[col]) }
        }
        var prev = BigInt.ONE
        for (k in 0 until m) {
            if (a[k][k].signum() == 0) {
                var p = -1
                for (i in k + 1 until m) {
                    if (a[i][k].signum() != 0) {
                        p = i
                        break
                    }
                }
                if (p == -1) return null // singular basis
                val tr = a[k]
                a[k] = a[p]
                a[p] = tr // swap equations; the solution is unchanged
            }
            val pivot = a[k][k]
            for (i in k + 1 until m) {
                val aik = a[i][k]
                for (j in k + 1..m) a[i][j] = (pivot * a[i][j] - aik * a[k][j]).divExact(prev)
                a[i][k] = BigInt.ZERO
            }
            prev = pivot
        }
        // Cheap O(m²) rational back-substitution on the (equivalent) upper-triangular system.
        val x = arrayOfNulls<BigRational>(m)
        for (i in m - 1 downTo 0) {
            var acc = BigRational.of(a[i][m])
            for (j in i + 1 until m) acc -= BigRational.of(a[i][j]) * x[j]!!
            x[i] = acc / BigRational.of(a[i][i])
        }
        return Array(m) { x[it]!! }
    }

    /** `A_full[row][col]`: structural column → `a[row][col]`, slack column `n+s` → unit `e_s`. */
    private fun fullEntry(model: LpModel, row: Int, col: Int): Long = if (col < model.n) {
        model.a[row][col]
    } else if (col - model.n == row) {
        1L
    } else {
        0L
    }
}
