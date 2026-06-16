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

    /**
     * Certified-exact quantities at a (float-found) optimal [basis], for the sparse path's
     * reduced-cost fixing / objective-bound reasons (#705): the exact objective lower bound
     * ([Certificate.objective] — tight at an optimal basis, i.e. the LP optimum), each variable's
     * reduced cost (`0` for basic columns), and which rows carry nonzero dual weight. Null when the
     * dual system is singular (bad float basis) or a negative reduced cost meets an infinite upper
     * bound (unbounded Lagrangian) — the caller then skips fixing, which is sound.
     */
    fun certify(model: LpModel, basis: Basis): Certificate? {
        val m = model.m
        val basic = basis.basicVars
        val y = solveDual(model, basic) ?: return null
        var l = BigRational.ZERO
        for (i in 0 until m) l += y[i] * BigRational.of(model.rhs[i])
        val nonBasic = BooleanArray(model.numVars) { true }
        for (t in 0 until m) nonBasic[basic[t]] = false
        val reducedCost = Array(model.numVars) { BigRational.ZERO }
        for (j in 0 until model.numVars) {
            if (!nonBasic[j]) continue
            var dot = BigRational.ZERO
            forEachFullColumn(model, j) { i, a -> dot += y[i] * BigRational.of(a) }
            val dj = BigRational.of(model.cost[j]) - dot // reduced cost c_j − yᵀA_j
            reducedCost[j] = dj
            if (dj.signum() < 0) {
                if (!model.hasUpper[j]) return null // unbounded below
                l += dj * BigRational.of(model.upper[j])
            }
        }
        val dualNonzeroRow = BooleanArray(m) { y[it].signum() != 0 }
        return Certificate(l + BigRational.of(model.objConstant), reducedCost, dualNonzeroRow)
    }

    /** Exact LP-optimum data certified from an optimal [Basis]: see [certify]. */
    class Certificate(
        /** The exact objective lower bound, tight at an optimal basis (= the LP optimum). */
        val objective: BigRational,
        /** Per-variable reduced cost `c_j − yᵀA_j`; `0` for basic columns. */
        val reducedCost: Array<BigRational>,
        /** Whether row `i` carries nonzero dual weight (for non-global-row premise citation). */
        val dualNonzeroRow: BooleanArray,
    )

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
            forEachFullColumn(model, j) { i, a -> dot += y[i] * BigRational.of(a) }
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
            // Column `col` over rows 0 until m (scatter the nonzeros), with the dual rhs c_col at m.
            val rowArr = Array(m + 1) { BigInt.ZERO }
            rowArr[m] = BigInt.of(model.cost[col])
            forEachFullColumn(model, col) { i, v -> rowArr[i] = BigInt.of(v) }
            rowArr
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
        // Cheap O(m²) rational back-substitution on the (equivalent) upper-triangular system; x[j]
        // for j > i is already final when row i is processed (we go bottom-up), so ZERO-init is safe.
        val x = Array(m) { BigRational.ZERO }
        for (i in m - 1 downTo 0) {
            var acc = BigRational.of(a[i][m])
            for (j in i + 1 until m) acc -= BigRational.of(a[i][j]) * x[j]
            x[i] = acc / BigRational.of(a[i][i])
        }
        return x
    }

    /** Iterate the nonzero rows of full column [col] as `(row, value)`: a structural column through the
     *  model's CSC/dense accessor, a slack column `n+s` as the unit vector `e_s`. */
    private inline fun forEachFullColumn(model: LpModel, col: Int, action: (row: Int, value: Long) -> Unit) {
        if (col < model.n) model.forEachInColumn(col, action) else action(col - model.n, 1L)
    }
}
