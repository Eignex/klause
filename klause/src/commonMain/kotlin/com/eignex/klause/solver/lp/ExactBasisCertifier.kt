package com.eignex.klause.solver.lp

import com.eignex.klause.solver.lp.cut.Cut
import com.eignex.klause.solver.lp.relaxation.LpExplanation
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.BigRational
import com.eignex.klause.util.IntArrayList

/**
 * Exact lower bound on the minimized objective `cᵀz`, certified from a (float-found) [Basis] using
 * exact rationals — the authoritative bound behind the float [RevisedSimplex] solve, immune to the
 * floating-point error that the cheap [safeObjectiveLowerBound] worst-cases against.
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
     * Certified-exact quantities at a (float-found) optimal [basis], for the LP's
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

    /**
     * Exactly certify that the node LP `{A x = rhs, 0 ≤ x ≤ upper}` is **infeasible**, from a (float)
     * basis and the leaving row at a dual-unbounded termination ([RevisedSimplex.infeasibleRow]). The
     * candidate Farkas ray is `ρ = B⁻ᵀ e_r` solved exactly; by Farkas' lemma the LP is infeasible iff
     * some `ρ` has `ρ·rhs > Σ_j max(0, ρ·A_j)·u_j`, which is checked exactly here (both `±ρ`). **Any**
     * `ρ` passing it proves infeasibility, so a bad/float-misled ray simply fails the check and the
     * node is kept — the prune is sound regardless of how the ray was found. Returns false on a
     * singular basis or when neither sign certifies.
     */
    fun certifiesInfeasible(model: LpModel, basis: Basis, leavingRow: Int): Boolean =
        farkasRay(model, basis, leavingRow) != null

    /**
     * The exact Farkas ray `ρ` (either sign of `B⁻ᵀ e_r`) that proves the node LP infeasible, or null
     * when neither sign certifies / the basis is singular. The returned `ρ` satisfies
     * `ρ·rhs > Σ_j max(0, ρ·A_j)·u_j`, so its column/row support is a sufficient infeasibility reason
     * ([LpExplanation.infeasibilityClause] turns it into a bound-atom nogood).
     */
    fun farkasRay(model: LpModel, basis: Basis, leavingRow: Int): Array<BigRational>? {
        if (leavingRow !in 0 until model.m) return null
        val rho = solveDualSystem(model, basis.basicVars) { t -> if (t == leavingRow) BigInt.ONE else BigInt.ZERO }
            ?: return null
        if (farkasCertifies(model, rho)) return rho
        val neg = Array(rho.size) { BigRational.ZERO - rho[it] }
        return if (farkasCertifies(model, neg)) neg else null
    }

    /** Whether [rho] is an exact Farkas infeasibility certificate: `ρ·rhs > Σ_j max(0, ρ·A_j)·u_j`. A
     *  column with `ρ·A_j > 0` but no finite upper bound makes the box max unbounded — this ρ cannot
     *  certify, so bail (false). */
    private fun farkasCertifies(model: LpModel, rho: Array<BigRational>): Boolean {
        var lhs = BigRational.ZERO
        for (i in 0 until model.m) lhs += rho[i] * BigRational.of(model.rhs[i])
        var boxMax = BigRational.ZERO
        for (j in 0 until model.numVars) {
            var aj = BigRational.ZERO
            forEachFullColumn(model, j) { i, a -> aj += rho[i] * BigRational.of(a) }
            if (aj.signum() > 0) {
                if (!model.hasUpper[j]) return false
                boxMax += aj * BigRational.of(model.upper[j])
            }
        }
        return lhs > boxMax
    }

    /**
     * Exact Gomory ([mir]=false) / mixed-integer-rounding ([mir]=true) cuts from the optimal [basis],
     * up to [maxCuts]. For each basic row whose basic variable is a structural (integer) column, the
     * exact tableau row `ā_ij = (B⁻ᵀe_i)·A_j` and basic value `b̄_i = (B⁻ᵀe_i)·rhsAdj` are computed in
     * rationals (no float rounding), so the fractional-row derivation is exact and the cut is rigorously
     * valid. The cut is built in shifted z-space, unshifted to the structural x-space, scaled to integer
     * `Long` coefficients, gcd-reduced and Chvátal-rounded; a cut whose integer form overflows `Long` is
     * dropped (a missed cut is sound). Tableau-derived, so [Cut.global] stays false — learning withholds.
     */
    fun tableauCuts(model: LpModel, basis: Basis, maxCuts: Int, mir: Boolean): List<Cut> {
        val m = model.m
        val n = model.n
        val basic = basis.basicVars
        val status = basis.status
        // rhsAdj = rhs − Σ_{nonbasic at upper} A_j·u_j, exactly as the revised simplex forms β's rhs.
        val rhsAdj = Array(m) { BigRational.of(model.rhs[it]) }
        for (j in 0 until model.numVars) {
            if (status[j] == VarStatus.AT_UPPER) {
                forEachFullColumn(model, j) { i, a -> rhsAdj[i] -= BigRational.of(a) * BigRational.of(model.upper[j]) }
            }
        }
        // Row-major view of the structural matrix, for back-substituting a ≤-row slack to its columns.
        val rowCols = Array(m) { IntArrayList() }
        val rowVals = Array(m) { ArrayList<Long>() }
        for (k in 0 until n) {
            model.forEachInColumn(k) { i, v ->
                rowCols[i].add(k)
                rowVals[i].add(v)
            }
        }
        val cuts = ArrayList<Cut>()
        val one = BigRational.of(1L)
        // Sparse accumulator reused across every cut in this call: `coef[k]` holds the (rational)
        // coefficient on shifted structural column `k` while a cut is built, `null` = untouched; only
        // the touched columns are visited and reset, so a sparse cut never scans all `n` columns.
        val coef = arrayOfNulls<BigRational>(n)
        val touched = IntArrayList()
        for (i in 0 until m) {
            if (cuts.size >= maxCuts) break
            if (basic[i] >= n) continue // cut on fractional structural variables only
            val rho = solveDualSystem(model, basic) { t -> if (t == i) BigInt.ONE else BigInt.ZERO } ?: continue
            var bbar = BigRational.ZERO
            for (r in 0 until m) bbar += rho[r] * rhsAdj[r]
            val f0 = bbar - BigRational.of(bbar.floor())
            if (f0.signum() == 0) continue // integral basic value: no cut
            // The cut combines rows with weights ρ; it is globally valid iff ρ is zero on every
            // non-global row (a big-M reified row would otherwise make it only conditionally valid).
            var global = true
            for (r in 0 until m) {
                if (!model.rowGlobal[r] && rho[r].signum() != 0) {
                    global = false
                    break
                }
            }
            val cut = buildTableauCut(model, status, rho, rowCols, rowVals, f0, mir, one, global, coef, touched)
            if (cut != null) cuts.add(cut)
        }
        return cuts
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun buildTableauCut(
        model: LpModel,
        status: Array<VarStatus>,
        rho: Array<BigRational>,
        rowCols: Array<IntArrayList>,
        rowVals: Array<ArrayList<Long>>,
        f0: BigRational,
        mir: Boolean,
        one: BigRational,
        global: Boolean,
        coef: Array<BigRational?>, // shared sparse accumulator over shifted structural columns (n-wide)
        touched: IntArrayList, // the columns this cut wrote into `coef`, for O(touched) read + reset
    ): Cut? {
        val n = model.n

        // Accumulate `value` onto shifted structural column `k`, recording the first touch.
        fun add(k: Int, value: BigRational) {
            val cur = coef[k]
            if (cur == null) {
                coef[k] = value
                touched.add(k)
            } else {
                coef[k] = cur + value
            }
        }
        var constant = BigRational.ZERO // constant moved to the left side
        for (j in 0 until model.numVars) {
            if (status[j] == VarStatus.BASIC) continue
            var aij = BigRational.ZERO
            forEachFullColumn(model, j) { i, a -> aij += rho[i] * BigRational.of(a) }
            val atLower = status[j] == VarStatus.AT_LOWER
            val aClassic = if (atLower) aij else BigRational.ZERO - aij // x_v + Σ a_j t_j = b̄, t_j ≥ 0
            val fj = aClassic - BigRational.of(aClassic.floor())
            if (fj.signum() == 0) continue
            // Gomory multiplier f_j; MIR rounds it down past f0: φ(a_j) = f0·(1−f_j)/(1−f0).
            val mj = if (!mir || fj <= f0) fj else f0 * (one - fj) / (one - f0)
            if (mj.signum() == 0) continue
            if (j < n) {
                if (atLower) {
                    add(j, mj) // t_j = z_j
                } else {
                    add(j, BigRational.ZERO - mj) // t_j = u_j − z_j
                    constant += mj * BigRational.of(model.upper[j])
                }
            } else {
                if (model.hasUpper[j]) continue // equality-row slack fixed at 0 ⇒ t_j = 0
                val r = j - n // ≤-row slack: t_j = rhs_r − Σ_k A_rk·z_k
                val cols = rowCols[r]
                val vals = rowVals[r]
                for (idx in 0 until cols.size) add(cols[idx], BigRational.ZERO - mj * BigRational.of(vals[idx]))
                constant += mj * BigRational.of(model.rhs[r])
            }
        }
        // Σ coef_k·z_k ≥ f0 − constant, then unshift z_k = x_k − loShift_k. Touched columns ascending
        // so the emitted cut is column-ordered and deterministic.
        val ks = touched.toIntArray()
        ks.sort()
        var rhs = f0 - constant
        for (k in ks) {
            val c = coef[k] ?: continue
            if (c.signum() != 0) rhs += c * BigRational.of(model.loShift[k])
        }
        val cut = integerCut(coef, ks, rhs, global)
        for (idx in 0 until touched.size) coef[touched[idx]] = null // reset for the next cut
        touched.clear()
        return cut
    }

    /** Scale a rational cut `Σ coef_k·x_k ≥ rhs` (over the touched columns [ks], ascending) to integer
     *  `Long` coefficients (common-denominator clear, gcd-reduce, ceil the rhs — valid since the reduced
     *  left side is integral), or null if it has no term or overflows `Long`. */
    private fun integerCut(coef: Array<BigRational?>, ks: IntArray, rhs: BigRational, global: Boolean): Cut? {
        var denLcm = BigInt.ONE
        for (k in ks) {
            val c = coef[k] ?: continue
            if (c.signum() != 0) denLcm = lcm(denLcm, c.den)
        }
        denLcm = lcm(denLcm, rhs.den)
        val intCoef = arrayOfNulls<BigInt>(ks.size) // parallel to ks
        var g = BigInt.ZERO
        var count = 0
        for (idx in ks.indices) {
            val c = coef[ks[idx]] ?: continue
            if (c.signum() == 0) continue
            val ci = c.num * denLcm.divExact(c.den)
            intCoef[idx] = ci
            g = g.gcd(ci)
            count++
        }
        if (count == 0) return null
        if (g.signum() == 0) g = BigInt.ONE
        val rhsInt = rhs.num * denLcm.divExact(rhs.den)
        val cols = IntArray(count)
        val vals = LongArray(count)
        var oi = 0
        for (idx in ks.indices) {
            val ci = intCoef[idx] ?: continue
            val v = ci.divExact(g).toLongOrNull() ?: return null // overflow ⇒ drop (sound)
            cols[oi] = ks[idx]
            vals[oi] = v
            oi++
        }
        // ceil(rhsInt / g) for g > 0: the reduced left side is integral, so rounding up stays valid.
        val q = rhsInt.div(g)
        val ceilRhs = if (rhsInt.rem(g).signum() != 0 && rhsInt.signum() > 0) q + BigInt.ONE else q
        val rhsLong = ceilRhs.toLongOrNull() ?: return null
        return Cut(cols, vals, Relation.GE, rhsLong, global)
    }

    private fun lcm(a: BigInt, b: BigInt): BigInt {
        if (a.signum() == 0 || b.signum() == 0) return BigInt.ZERO
        return a.divExact(a.gcd(b)) * b
    }

    /** Exact solve of `Bᵀ y = rhs` with `Bᵀ`'s row `t` the basic column `basic[t]`: fraction-free
     *  Bareiss forward elimination (integer, no gcd) then rational back-substitution. */
    private fun solveDual(model: LpModel, basic: IntArray): Array<BigRational>? =
        solveDualSystem(model, basic) { t -> BigInt.of(model.cost[basic[t]]) }

    private fun solveDualSystem(model: LpModel, basic: IntArray, rhs: (Int) -> BigInt): Array<BigRational>? {
        val m = model.m
        // Augmented [Bᵀ | rhs] in BigInt; the O(m³) elimination below is fraction-free (Bareiss):
        // pure-integer, no per-op gcd, with magnitudes bounded by a single determinant.
        val a = Array(m) { t ->
            val col = basic[t]
            // Basic column `col` over rows 0 until m (scatter the nonzeros), with rhs(t) at column m.
            val rowArr = Array(m + 1) { BigInt.ZERO }
            rowArr[m] = rhs(t)
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
     *  model's CSC accessor, a slack column `n+s` as the unit vector `e_s`. */
    private inline fun forEachFullColumn(model: LpModel, col: Int, action: (row: Int, value: Long) -> Unit) {
        if (col < model.n) model.forEachInColumn(col, action) else action(col - model.n, 1L)
    }
}
