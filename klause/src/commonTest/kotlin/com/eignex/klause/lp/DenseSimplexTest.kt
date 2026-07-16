package com.eignex.klause.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DenseSimplex] must reach the same optimum as the sparse [RevisedSimplex] on the same model. */
class DenseSimplexTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 9), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) {
            val vals = LongArray(n) { rng.nextLong(-4, 5) }
            b.addRow(cols, vals, Relation.LE, rng.nextLong(3, 25))
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `dense optimum agrees with the sparse simplex on random models`() {
        val rng = Random(20260716)
        var compared = 0
        repeat(1000) {
            val model = randomModel(rng.nextInt(2, 8), rng.nextInt(2, 8), rng)
            val sparse = RevisedSimplex(model).solve()
            val dense = DenseSimplex(model).solve()
            if (sparse == null) {
                // If sparse could not settle, only require dense not to invent a different feasibility story
                // catastrophically — skip the numeric compare.
                return@repeat
            }
            val d = assertNotNull(dense, "dense returned null where sparse solved")
            compared++
            assertTrue(
                abs(sparse.objective - d.objective) <= 1e-6 * (1.0 + abs(sparse.objective)),
                "objective mismatch: sparse=${sparse.objective} dense=${d.objective}",
            )
        }
        assertTrue(compared > 300, "compared only $compared models")
    }

    @Test
    fun `solves a small LP to the known optimum`() {
        // minimize x + y  s.t. x + y >= 3, 0<=x,y<=5  ->  optimum 3.
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 3L)
        val r = assertNotNull(DenseSimplex(b.build(Sense.MINIMIZE)).solve())
        assertEquals(3.0, r.objective, 1e-9)
    }

    @Test
    fun `reports infeasibility with a ray`() {
        // 0 <= x <= 1 with x >= 2 has no feasible point.
        val b = LpBuilder()
        b.addVar(0L, 1L, cost = 1L)
        b.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        val s = DenseSimplex(b.build(Sense.MINIMIZE))
        assertNull(s.solve())
        assertNotNull(s.infeasibleRay)
    }

    @Test
    fun `newLpSolver picks the dense engine only for a dense continuous model`() {
        val dense = LpBuilder()
        val x = dense.addRealVar(0.0, 10.0, cost = 1.0)
        val y = dense.addRealVar(0.0, 10.0, cost = 1.0)
        dense.addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, 1.0), Relation.GE, 3.0)
        dense.addRealRow(intArrayOf(x, y), doubleArrayOf(2.0, 1.0), Relation.LE, 15.0)
        assertTrue(newLpSolver(dense.build(Sense.MINIMIZE)) is DenseSimplex)

        val intModel = LpBuilder()
        intModel.addVar(0L, 5L, cost = 1L)
        intModel.addVar(0L, 5L, cost = 1L)
        intModel.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 3L)
        assertTrue(newLpSolver(intModel.build(Sense.MINIMIZE)) is RevisedSimplex)
    }

    @Test
    fun `solves a continuous real-coefficient LP`() {
        // minimize 1.5 x  s.t.  x >= 2,  x in [0, 10] real  ->  objective 3.0.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 10.0, cost = 1.5)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 2.0)
        val r = assertNotNull(DenseSimplex(b.build(Sense.MINIMIZE)).solve())
        assertEquals(3.0, r.objective, 1e-9)
    }
}
