package com.eignex.klause.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [EtaBasis] product-form updates validated against the ground truth: after a sequence of column
 * replacements, its FTRAN/BTRAN must match a fresh [SparseLu] factorization of the same updated basis.
 * Also checks that running [RevisedSimplex] through the eta chain reaches the same optimum as forcing a
 * refactorization every pivot — the path the chain is meant to make cheaper, not change.
 */
class EtaBasisTest {

    /** Diagonally dominant ⇒ non-singular; ~50% off-diagonal fill. `col[t][i]` = entry (row i, col t). */
    private fun randomColumns(m: Int, rng: Random): Array<DoubleArray> {
        val col = Array(m) { DoubleArray(m) }
        for (t in 0 until m) {
            for (i in 0 until m) {
                col[t][i] = when {
                    i == t -> rng.nextInt(1, 5).toDouble() + 2.0 * m
                    rng.nextInt(2) == 0 -> rng.nextInt(-4, 5).toDouble()
                    else -> 0.0
                }
            }
        }
        return col
    }

    private fun factor(col: Array<DoubleArray>, m: Int): SparseLu? {
        val rows = Array(m) { HashMap<Int, Double>() }
        for (t in 0 until m) for (i in 0 until m) if (col[t][i] != 0.0) rows[i][t] = col[t][i]
        return SparseLu.factorize(rows, m)
    }

    @Test
    fun `eta updates match a fresh factorization after column replacements`() {
        val rng = Random(20260621)
        repeat(200) {
            val m = rng.nextInt(2, 11)
            val col = randomColumns(m, rng)
            val base = factor(col, m)
            assertNotNull(base, "base factorize returned null on a non-singular matrix")
            val eta = EtaBasis.of(base, m)

            repeat(rng.nextInt(1, m + 4)) {
                // Entering column; spike η = B⁻¹ A_q via the maintained factor, pivot = its largest entry.
                val bump = rng.nextInt(m)
                val aq = DoubleArray(m) { idx -> rng.nextInt(-4, 5).toDouble() + if (idx == bump) 6.0 else 0.0 }
                val spike = eta.ftran(aq)
                var p = 0
                for (i in 1 until m) if (abs(spike[i]) > abs(spike[p])) p = i
                if (abs(spike[p]) < 0.5) return@repeat // skip an unstable pivot

                for (i in 0 until m) col[p][i] = aq[i] // basis slot p now holds A_q
                eta.update(p, spike)

                val fresh = factor(col, m) ?: return@repeat
                val rhs = DoubleArray(m) { rng.nextInt(-5, 6).toDouble() }
                val f = eta.ftran(rhs)
                val fo = fresh.ftran(rhs)
                for (i in 0 until m) {
                    assertTrue(abs(f[i] - fo[i]) <= 1e-6 * (1 + abs(fo[i])), "ftran[$i] eta=${f[i]} fresh=${fo[i]}")
                }
                val b = eta.btran(rhs)
                val bo = fresh.btran(rhs)
                for (i in 0 until m) {
                    assertTrue(abs(b[i] - bo[i]) <= 1e-6 * (1 + abs(bo[i])), "btran[$i] eta=${b[i]} fresh=${bo[i]}")
                }
            }
        }
    }

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 8), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) { b.addRow(cols, LongArray(n) { rng.nextLong(-3, 4) }, Relation.LE, rng.nextLong(3, 20)) }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `eta path reaches the same optimum as refactorizing every pivot`() {
        val rng = Random(20260622)
        var compared = 0
        repeat(500) {
            val model = randomModel(rng.nextInt(3, 9), rng.nextInt(3, 9), rng)
            val withEta = RevisedSimplex(model).solve()
            val perPivot = RevisedSimplex(model, refactorEtaLimit = 1).solve()
            if (withEta == null || perPivot == null) return@repeat
            compared++
            assertTrue(
                abs(withEta.objective - perPivot.objective) <= 1e-6 * maxOf(1.0, abs(perPivot.objective)),
                "eta obj ${withEta.objective} vs per-pivot ${perPivot.objective}",
            )
        }
        assertTrue(compared > 200, "compared on only $compared instances")
    }
}
