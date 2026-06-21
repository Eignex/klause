package com.eignex.klause.solver.lp

import com.eignex.klause.util.BigRational
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Exact BigInt basis-certification (#567 / #705): at the float-optimal basis the certify is tight,
 *  and [ExactBasisCertifier.lowerBoundCeil] is exactly `ceil(LP optimum)` — a sound integer bound. */
class ExactBasisCertifierTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 8), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) { b.addRow(cols, LongArray(n) { rng.nextLong(-3, 4) }, Relation.LE, rng.nextLong(3, 20)) }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `certify is tight at the optimal basis and the ceil bound is sound`() {
        val rng = Random(20260616)
        var checked = 0
        repeat(800) {
            val model = randomModel(rng.nextInt(3, 8), rng.nextInt(3, 8), rng)
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            val cert = ExactBasisCertifier.certify(model, rev.basis) ?: return@repeat
            val ceil = ExactBasisCertifier.lowerBoundCeil(model, rev.basis) ?: return@repeat
            checked++
            val exact = cert.objective.num.toDouble() / cert.objective.den.toDouble()
            assertTrue(
                abs(exact - rev.objective) <= 1e-6 * maxOf(1.0, abs(exact)),
                "certify $exact vs float ${rev.objective}",
            )
            assertTrue(
                ceil.toDouble() >= exact - 1e-6 && ceil.toDouble() < exact + 1.0 + 1e-6,
                "ceil $ceil not round(optimum $exact)",
            )
        }
        assertTrue(checked > 300, "covered only $checked instances")
    }

    @Test
    fun `the exact dual solve matches an independent dense-rational oracle`() {
        // #34: the sparse det-scaled exact dual solve (which replaced the dense Bareiss) must agree
        // value-for-value with a plain dense BigRational Gaussian elimination — a different algorithm,
        // independent of both the production solve and the deleted Bareiss.
        val rng = Random(343434)
        var checked = 0
        repeat(500) {
            val model = randomModel(rng.nextInt(2, 6), rng.nextInt(2, 6), rng)
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            val basic = rev.basis.basicVars
            val m = model.m
            val cB = LongArray(m) { t -> model.cost[basic[t]] }
            val y = ExactBasisCertifier.exactDualForTest(model, basic, cB) ?: return@repeat
            val oracle = denseRationalDual(model, basic, cB) ?: return@repeat
            checked++
            for (i in 0 until m) {
                assertTrue(
                    y[i].compareTo(oracle[i]) == 0,
                    "dual[$i] ${y[i].num}/${y[i].den} vs oracle ${oracle[i].num}/${oracle[i].den}",
                )
            }
        }
        assertTrue(checked > 100, "covered only $checked instances")
    }

    /** Independent exact oracle: dense BigRational Gauss–Jordan on `Bᵀ y = rhs` (row `t` is the basic
     *  column `basic[t]`). A different algorithm from the production det-scaled solve, so agreement is
     *  real cross-validation. Null on a singular basis. */
    private fun denseRationalDual(model: LpModel, basic: IntArray, rhs: LongArray): Array<BigRational>? {
        val m = model.m
        val a = Array(m) { Array(m + 1) { BigRational.ZERO } }
        for (t in 0 until m) {
            a[t][m] = BigRational.of(rhs[t])
            val col = basic[t]
            if (col < model.n) {
                model.forEachInColumn(col) { i, v -> a[t][i] = BigRational.of(v) }
            } else {
                a[t][col - model.n] = BigRational.of(1L)
            }
        }
        for (k in 0 until m) {
            var p = -1
            for (i in k until m) {
                if (a[i][k].signum() != 0) {
                    p = i
                    break
                }
            }
            if (p == -1) return null
            val tmp = a[k]
            a[k] = a[p]
            a[p] = tmp
            val piv = a[k][k]
            for (i in 0 until m) {
                if (i == k || a[i][k].signum() == 0) continue
                val f = a[i][k] / piv
                for (j in k..m) a[i][j] = a[i][j] - f * a[k][j]
            }
        }
        return Array(m) { a[it][m] / a[it][it] }
    }
}
