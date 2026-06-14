package com.eignex.klause.solver.lp

import kotlin.math.abs

/**
 * Result of a [RevisedSimplex] solve: the optimal [basis] (to warm-start or exactly certify), the
 * float objective, and the dual vector `y` (one per row) used by the Neumaier–Shcherbina safe
 * bound. All values are double-precision; the authoritative bound comes from exact certification of
 * [basis], never from these.
 */
internal class FloatLpResult(val basis: Basis, val objective: Double, val duals: DoubleArray)

/**
 * Double-precision bounded-variable **dual** simplex in *revised* form: it maintains the explicit
 * basis inverse `B⁻¹` (dense `m × m`) and the constraint columns in sparse CSC, instead of the full
 * `m × (n+m)` dense tableau [FloatSimplex] carries. The decision logic — slack cold start, most-
 * violated leaving variable, dual ratio-test entering variable — is identical to [FloatSimplex];
 * only the linear algebra is revised, so for `n ≫ m` it avoids materializing the wide tableau.
 *
 * Like [FloatSimplex] it is a heuristic that can return null (non-convergence / dual-unbounded);
 * its [basis] is then certified exactly downstream, so float rounding here is never safety-critical.
 *
 * NOTE: `B⁻¹` is still dense (`m²`), so the per-iteration cost and memory match the tableau when
 * `m ≈ n`; replacing the explicit inverse with a sparse LU factorization is the remaining scaling
 * step. What lands here is the float revised core + the duals the safe bound needs.
 */
internal class RevisedSimplex(private val model: LpModel) {
    private val m = model.m
    private val n = model.n
    private val numVars = model.numVars

    // Sparse CSC of the structural columns; slack column n+i is the unit vector e_i (implicit).
    private val colRows: Array<IntArray>
    private val colVals: Array<DoubleArray>

    private val basicVar = IntArray(m)
    private val status = Array(numVars) { VarStatus.BASIC }
    private val binv = Array(m) { DoubleArray(m) }

    init {
        colRows = Array(n) { IntArray(0) }
        colVals = Array(n) { DoubleArray(0) }
        for (j in 0 until n) {
            var nnz = 0
            for (i in 0 until m) if (model.a[i][j] != 0L) nnz++
            val rows = IntArray(nnz)
            val vals = DoubleArray(nnz)
            var k = 0
            for (i in 0 until m) {
                val v = model.a[i][j]
                if (v != 0L) {
                    rows[k] = i
                    vals[k] = v.toDouble()
                    k++
                }
            }
            colRows[j] = rows
            colVals[j] = vals
        }
    }

    /** `B⁻¹ · A_j` (FTRAN) into [out]; A_j is a structural CSC column or a slack unit column. */
    private fun ftran(j: Int, out: DoubleArray) {
        for (i in 0 until m) out[i] = 0.0
        if (j >= n) {
            val col = j - n
            for (i in 0 until m) out[i] = binv[i][col]
            return
        }
        val rows = colRows[j]
        val vals = colVals[j]
        for (k in rows.indices) {
            val r = rows[k]
            val a = vals[k]
            for (i in 0 until m) out[i] += binv[i][r] * a
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

    fun solve(): FloatLpResult? {
        coldStart()
        val maxIter = 50 * (m + numVars) + 200
        val beta = DoubleArray(m)
        val y = DoubleArray(m)
        val rhsAdj = DoubleArray(m)
        val alpha = DoubleArray(m)
        var iter = 0
        while (iter++ < maxIter) {
            // β = B⁻¹ (b − Σ_{j nonbasic at upper} A_j·u_j)
            for (i in 0 until m) rhsAdj[i] = model.rhs[i].toDouble()
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.AT_UPPER) {
                    val u = model.upper[j].toDouble()
                    if (j >= n) {
                        rhsAdj[j - n] -= u
                    } else {
                        val rows = colRows[j]
                        val vals = colVals[j]
                        for (k in rows.indices) rhsAdj[rows[k]] -= vals[k] * u
                    }
                }
            }
            for (i in 0 until m) {
                var acc = 0.0
                val bi = binv[i]
                for (t in 0 until m) acc += bi[t] * rhsAdj[t]
                beta[i] = acc
            }
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
            if (r == -1) return optimal(beta) // primal feasible ⇒ optimal

            // y = c_B · B⁻¹  (row vector), then reduced cost d_j = c_j − y·A_j.
            computeDuals(y)
            // Pivot row ρ = e_r^T B⁻¹; entering column by dual ratio test.
            val rho = binv[r]
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
            if (q == -1) return null // dual unbounded ⇒ primal infeasible; let the exact solver judge

            ftran(q, alpha)
            if (abs(alpha[r]) < TOL) return null // numerically singular pivot
            status[basicVar[r]] = if (belowLower) VarStatus.AT_LOWER else VarStatus.AT_UPPER
            updateInverse(r, alpha)
            basicVar[r] = q
            status[q] = VarStatus.BASIC
        }
        return null // budget exhausted
    }

    private fun optimal(beta: DoubleArray): FloatLpResult {
        var obj = 0.0
        for (j in 0 until numVars) {
            val c = model.cost[j]
            if (c != 0L && status[j] == VarStatus.AT_UPPER) obj += c.toDouble() * model.upper[j].toDouble()
        }
        for (i in 0 until m) {
            val c = model.cost[basicVar[i]]
            if (c != 0L) obj += c.toDouble() * beta[i]
        }
        val y = DoubleArray(m)
        computeDuals(y)
        return FloatLpResult(Basis(basicVar.copyOf(), status.copyOf()), obj, y)
    }

    private fun computeDuals(y: DoubleArray) {
        for (t in 0 until m) {
            var acc = 0.0
            for (i in 0 until m) {
                val cb = model.cost[basicVar[i]]
                if (cb != 0L) acc += cb.toDouble() * binv[i][t]
            }
            y[t] = acc
        }
    }

    /** Product-form update of the explicit inverse after pivoting in row [r] with FTRAN column [alpha]. */
    private fun updateInverse(r: Int, alpha: DoubleArray) {
        val p = alpha[r]
        val br = binv[r]
        for (t in 0 until m) br[t] /= p
        for (i in 0 until m) {
            if (i == r) continue
            val f = alpha[i]
            if (abs(f) < TOL) continue
            val bi = binv[i]
            for (t in 0 until m) bi[t] -= f * br[t]
        }
    }

    private fun coldStart() {
        for (i in 0 until m) {
            for (t in 0 until m) binv[i][t] = if (i == t) 1.0 else 0.0
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until n) {
            status[j] = if (model.cost[j] >= 0L) VarStatus.AT_LOWER else VarStatus.AT_UPPER
        }
    }

    private companion object {
        const val TOL: Double = 1e-7
    }
}
