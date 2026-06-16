package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Cancellation
import kotlin.math.abs

/**
 * Result of a [RevisedSimplex] solve: the optimal [basis] (to warm-start or exactly certify), the
 * float objective, and the dual vector `y` (one per row) used by the Neumaier–Shcherbina safe
 * bound. All values are double-precision; the authoritative bound comes from exact certification of
 * [basis], never from these.
 */
internal class FloatLpResult(val basis: Basis, val objective: Double, val duals: DoubleArray)

/**
 * Double-precision bounded-variable **dual** simplex in *revised* form: the basis is held as a
 * sparse LU factorization ([SparseLu], `O(nnz)` memory) and the constraint columns in sparse CSC,
 * instead of the full `m × (n+m)` dense tableau [FloatSimplex] carries or an explicit dense `B⁻¹`.
 * The decision logic — slack cold start, most-violated leaving variable, dual ratio-test entering
 * variable — is identical to [FloatSimplex]; only the linear algebra is revised (FTRAN/BTRAN via the
 * LU), so it scales to large sparse models without materializing an `m²` structure.
 *
 * Like [FloatSimplex] it is a heuristic that can return null (non-convergence / dual-unbounded /
 * singular basis); its [FloatLpResult.basis] is then certified exactly downstream, so float rounding is never
 * safety-critical.
 *
 * NOTE: the basis is refactorized from scratch each iteration (correct and sparse; fine for the
 * warm-started few-pivot search). Forrest–Tomlin / eta updates between refactorizations are the
 * remaining speed step.
 */
internal class RevisedSimplex(
    private val model: LpModel,
    private val cancellation: Cancellation = Cancellation.Never,
) {
    private val m = model.m
    private val n = model.n
    private val numVars = model.numVars

    // Sparse CSC of the structural columns; slack column n+i is the unit vector e_i (implicit).
    private val colRows: Array<IntArray>
    private val colVals: Array<DoubleArray>

    private val basicVar = IntArray(m)
    private val status = Array(numVars) { VarStatus.BASIC }

    /** When [solve] returns null because the primal is infeasible (dual unbounded — no entering column
     *  for the most-violated basic row), the basis and that leaving row at termination, for the exact
     *  Farkas infeasibility check ([ExactBasisCertifier.certifiesInfeasible]). Null on any other failure
     *  (non-convergence, singular pivot, budget) — so the caller only prunes on a genuine infeasibility. */
    var infeasibleBasis: Basis? = null
        private set
    var infeasibleRow: Int = -1
        private set

    init {
        colRows = Array(n) { IntArray(0) }
        colVals = Array(n) { DoubleArray(0) }
        // Read columns through the model's representation-agnostic accessor: a direct CSC slice on a
        // sparse model (#602), or a dense-column scan otherwise. Two passes (the accessor is inline,
        // so each is a tight loop) — count nnz, then fill.
        for (j in 0 until n) {
            var nnz = 0
            model.forEachInColumn(j) { _, _ -> nnz++ }
            val rows = IntArray(nnz)
            val vals = DoubleArray(nnz)
            var k = 0
            model.forEachInColumn(j) { i, v ->
                rows[k] = i
                vals[k] = v.toDouble()
                k++
            }
            colRows[j] = rows
            colVals[j] = vals
        }
    }

    /** Dense original-row column `A_full[*][j]` into [out] (structural via CSC, slack as unit). */
    private fun denseColumn(j: Int, out: DoubleArray) {
        for (i in 0 until m) out[i] = 0.0
        if (j >= n) {
            out[j - n] = 1.0
        } else {
            val rows = colRows[j]
            val vals = colVals[j]
            for (k in rows.indices) out[rows[k]] = vals[k]
        }
    }

    /** `y · A_j` for the dual vector [y]; column j structural (CSC) or slack (single entry). */
    private fun dotColumn(y: DoubleArray, j: Int): Double {
        if (j >= n) return y[j - n]
        var acc = 0.0
        val rows = colRows[j]
        val vals = colVals[j]
        for (k in rows.indices) acc += y[rows[k]] * vals[k]
        return acc
    }

    /** Sparse LU of the current basis `B` (`B[i][t] = A_full[i][basicVar[t]]`); null if singular. */
    private fun factorizeBasis(): SparseLu? {
        val rows = Array(m) { HashMap<Int, Double>() }
        for (t in 0 until m) {
            val col = basicVar[t]
            if (col >= n) {
                rows[col - n][t] = 1.0
            } else {
                val rs = colRows[col]
                val vs = colVals[col]
                for (k in rs.indices) rows[rs[k]][t] = vs[k]
            }
        }
        return SparseLu.factorize(rows, m)
    }

    /** Duals `y` solving `Bᵀ y = c_B` (BTRAN). */
    private fun duals(lu: SparseLu): DoubleArray = lu.btran(DoubleArray(m) { model.cost[basicVar[it]].toDouble() })

    fun solve(): FloatLpResult? {
        coldStart()
        val maxIter = 50 * (m + numVars) + 200
        val rhsAdj = DoubleArray(m)
        val unit = DoubleArray(m)
        val aq = DoubleArray(m)
        var iter = 0
        while (iter++ < maxIter) {
            // Cooperative deadline: each iteration refactorizes (heavier than a single pivot), so an
            // unbounded loop on a large model would otherwise blow the wall-clock limit (#574). On
            // cancellation give up (null) — the basis is only a heuristic, so this is sound.
            if (iter % CANCEL_POLL == 0 && cancellation()) return null
            // Refactorize the basis each iteration (sparse, warm-started search ⇒ few iterations);
            // Forrest–Tomlin / eta updates between refactorizations are the remaining speed step.
            val lu = factorizeBasis() ?: return null
            // β = B⁻¹ (b − Σ_{j nonbasic at upper} A_j·u_j)
            for (i in 0 until m) rhsAdj[i] = model.rhs[i].toDouble()
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.AT_UPPER) {
                    val u = model.upper[j].toDouble()
                    if (j >= n) {
                        rhsAdj[j - n] -= u
                    } else {
                        val rs = colRows[j]
                        val vs = colVals[j]
                        for (k in rs.indices) rhsAdj[rs[k]] -= vs[k] * u
                    }
                }
            }
            val beta = lu.ftran(rhsAdj)
            // Leaving: most-violated basic bound (Dantzig).
            var r = -1
            var worst = TOL
            var belowLower = false
            for (i in 0 until m) {
                val v = basicVar[i]
                val below = -beta[i]
                val above = if (model.hasUpper[v]) beta[i] - model.upper[v].toDouble() else Double.NEGATIVE_INFINITY
                if (below > worst) {
                    worst = below
                    r = i
                    belowLower = true
                }
                if (above > worst) {
                    worst = above
                    r = i
                    belowLower = false
                }
            }
            if (r == -1) return optimal(beta, lu) // primal feasible ⇒ optimal

            val y = duals(lu)
            // Pivot row ρ = e_r^T B⁻¹ = B⁻ᵀ e_r; entering column by dual ratio test.
            for (i in 0 until m) unit[i] = if (i == r) 1.0 else 0.0
            val rho = lu.btran(unit)
            var q = -1
            var bestRatio = Double.MAX_VALUE
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC) continue
                val a = dotColumn(rho, j)
                if (abs(a) < TOL) continue
                val atLower = status[j] == VarStatus.AT_LOWER
                val eligible = if (belowLower) {
                    (atLower && a < 0) || (!atLower && a > 0)
                } else {
                    (atLower && a > 0) || (!atLower && a < 0)
                }
                if (!eligible) continue
                val dj = model.cost[j].toDouble() - dotColumn(y, j)
                val ratio = abs(dj / a)
                if (ratio < bestRatio) {
                    bestRatio = ratio
                    q = j
                }
            }
            if (q == -1) {
                // Dual unbounded ⇒ primal infeasible. Record the basis + leaving row so the caller can
                // certify infeasibility exactly (the float ray alone is not sound to prune on).
                infeasibleBasis = Basis(basicVar.copyOf(), status.copyOf())
                infeasibleRow = r
                return null
            }

            denseColumn(q, aq)
            val alpha = lu.ftran(aq)
            if (abs(alpha[r]) < TOL) return null // numerically singular pivot
            status[basicVar[r]] = if (belowLower) VarStatus.AT_LOWER else VarStatus.AT_UPPER
            basicVar[r] = q
            status[q] = VarStatus.BASIC
        }
        return null // budget exhausted
    }

    private fun optimal(beta: DoubleArray, lu: SparseLu): FloatLpResult {
        var obj = 0.0
        for (j in 0 until numVars) {
            val c = model.cost[j]
            if (c != 0L && status[j] == VarStatus.AT_UPPER) obj += c.toDouble() * model.upper[j].toDouble()
        }
        for (i in 0 until m) {
            val c = model.cost[basicVar[i]]
            if (c != 0L) obj += c.toDouble() * beta[i]
        }
        return FloatLpResult(Basis(basicVar.copyOf(), status.copyOf()), obj, duals(lu))
    }

    private fun coldStart() {
        for (i in 0 until m) {
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until n) {
            status[j] = if (model.cost[j] >= 0L) VarStatus.AT_LOWER else VarStatus.AT_UPPER
        }
    }

    private companion object {
        const val TOL: Double = 1e-7

        /** Iterations between cooperative cancellation polls (each iteration refactorizes). */
        const val CANCEL_POLL: Int = 32
    }
}
