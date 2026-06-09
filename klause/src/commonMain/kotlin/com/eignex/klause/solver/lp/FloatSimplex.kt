package com.eignex.klause.solver.lp

import kotlin.math.abs

/**
 * Double-precision bounded-variable dual simplex (#18 float fast-path). It solves the same LP as
 * [DualSimplex] but in floating point, and returns only the **basis** it lands on — never a bound.
 * The exact [DualSimplex] is then warm-started from that basis ([DualSimplex.solve]) and re-optimizes
 * to the true optimum, so the reported bound is always exact regardless of any float rounding here.
 *
 * Because the float solve is purely a heuristic warm start, its correctness is not safety-critical:
 * a bad or singular basis just makes the exact solver cold-start (a missed speedup, never a wrong
 * answer). It therefore uses plain Gaussian pivoting with a tolerance and a Dantzig leaving rule, and
 * gives up (returns null) on non-convergence or an unbounded ratio test. The payoff is on larger LPs
 * where a float solve plus a near-zero-pivot exact certification beats a cold exact solve; on the
 * small dense per-node LPs it is off by default.
 */
internal class FloatSimplex(private val model: LpModel) {
    private val m = model.m
    private val numVars = model.numVars
    private val rhsCol = numVars
    private val nMat = Array(m) { DoubleArray(numVars + 1) }
    private val basicVar = IntArray(m)
    private val status = IntArray(numVars)

    /** Solve in double precision and return the basis reached, or null if it did not converge. */
    fun basis(): Basis? {
        coldStart()
        val maxIter = 50 * (m + numVars) + 200
        var iter = 0
        while (iter++ < maxIter) {
            val beta = DoubleArray(m)
            for (i in 0 until m) {
                var acc = nMat[i][rhsCol]
                for (j in 0 until numVars) {
                    if (status[j] == VarStatus.AT_UPPER) acc -= nMat[i][j] * model.upper[j].toDouble()
                }
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
            if (r == -1) return Basis(basicVar.copyOf(), status.copyOf()) // primal feasible ⇒ optimal

            // Entering: dual ratio test over eligible nonbasic columns.
            val row = nMat[r]
            var q = -1
            var bestRatio = Double.MAX_VALUE
            val reduced = reducedCosts()
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC) continue
                val a = row[j]
                if (abs(a) < TOL) continue
                val atLower = status[j] == VarStatus.AT_LOWER
                val eligible = if (belowLower) {
                    (atLower && a < 0) || (!atLower && a > 0)
                } else {
                    (atLower && a > 0) || (!atLower && a < 0)
                }
                if (!eligible) continue
                val ratio = abs(reduced[j] / a)
                if (ratio < bestRatio) {
                    bestRatio = ratio
                    q = j
                }
            }
            if (q == -1) return null // dual unbounded (primal infeasible) — let the exact solver judge

            status[basicVar[r]] = if (belowLower) VarStatus.AT_LOWER else VarStatus.AT_UPPER
            pivot(r, q)
            basicVar[r] = q
            status[q] = VarStatus.BASIC
        }
        return null // did not converge in budget
    }

    private fun coldStart() {
        for (i in 0 until m) {
            val rowi = nMat[i]
            for (j in 0 until model.n) rowi[j] = model.a[i][j].toDouble()
            for (s in 0 until m) rowi[model.n + s] = if (s == i) 1.0 else 0.0
            rowi[rhsCol] = model.rhs[i].toDouble()
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until model.n) {
            status[j] = if (model.cost[j] >= 0L) VarStatus.AT_LOWER else VarStatus.AT_UPPER
        }
    }

    private fun reducedCosts(): DoubleArray {
        val reduced = DoubleArray(numVars)
        for (j in 0 until numVars) {
            var acc = model.cost[j].toDouble()
            for (i in 0 until m) {
                val cb = model.cost[basicVar[i]]
                if (cb != 0L) acc -= cb.toDouble() * nMat[i][j]
            }
            reduced[j] = acc
        }
        return reduced
    }

    private fun pivot(r: Int, q: Int) {
        val prow = nMat[r]
        val p = prow[q]
        for (j in 0..numVars) prow[j] /= p
        for (i in 0 until m) {
            if (i == r) continue
            val row = nMat[i]
            val f = row[q]
            if (abs(f) < TOL) continue
            for (j in 0..numVars) row[j] -= f * prow[j]
        }
    }

    private companion object {
        const val TOL: Double = 1e-7
    }
}
