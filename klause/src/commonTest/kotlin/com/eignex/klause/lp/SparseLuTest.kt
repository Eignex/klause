package com.eignex.klause.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [SparseLu] FTRAN/BTRAN validated against known solutions of random (sparse, invertible) systems. */
class SparseLuTest {

    /** Diagonally dominant ⇒ non-singular; ~50% off-diagonal fill. */
    private fun randomMatrix(m: Int, rng: Random): Array<DoubleArray> = Array(m) { i ->
        DoubleArray(m) { j ->
            when {
                i == j -> (rng.nextInt(1, 5)).toDouble() + 2.0 * m
                rng.nextInt(2) == 0 -> rng.nextInt(-4, 5).toDouble()
                else -> 0.0
            }
        }
    }

    private fun rows(b: Array<DoubleArray>, m: Int): Array<HashMap<Int, Double>> = Array(m) { i ->
        HashMap<Int, Double>().also { row -> for (j in 0 until m) if (b[i][j] != 0.0) row[j] = b[i][j] }
    }

    @Test
    fun `ftran and btran solve random systems`() {
        val rng = Random(20260617)
        repeat(300) {
            val m = rng.nextInt(2, 18)
            val b = randomMatrix(m, rng)
            val lu = SparseLu.factorize(rows(b, m), m)
            assertNotNull(lu, "factorize returned null on a non-singular matrix")

            // FTRAN: pick x, form rhs = B x, recover x.
            val x = DoubleArray(m) { rng.nextInt(-5, 6).toDouble() }
            val rhs = DoubleArray(m) { i -> (0 until m).sumOf { j -> b[i][j] * x[j] } }
            val gotF = lu.ftran(rhs)
            for (j in 0 until m) {
                assertTrue(abs(gotF[j] - x[j]) <= 1e-7 * (1 + abs(x[j])), "ftran[$j]=${gotF[j]} want ${x[j]}")
            }

            // BTRAN: form rhs = Bᵀ x, recover x.
            val rhsT = DoubleArray(m) { j -> (0 until m).sumOf { i -> b[i][j] * x[i] } }
            val gotB = lu.btran(rhsT)
            for (i in 0 until m) {
                assertTrue(abs(gotB[i] - x[i]) <= 1e-7 * (1 + abs(x[i])), "btran[$i]=${gotB[i]} want ${x[i]}")
            }
        }
    }

    @Test
    fun `singular matrix returns null`() {
        val m = 4
        val b = Array(m) { DoubleArray(m) }
        // Row 2 is a copy of row 0 ⇒ singular.
        for (j in 0 until m) {
            b[0][j] = (j + 1).toDouble()
            b[2][j] = (j + 1).toDouble()
        }
        b[1][1] = 5.0
        b[3][3] = 7.0
        assertTrue(SparseLu.factorize(rows(b, m), m) == null)
    }
}
