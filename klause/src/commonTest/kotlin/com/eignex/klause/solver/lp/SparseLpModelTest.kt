package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LpBuilder.buildSparse] (CSC core, no dense `m × n`) must be faithful to [LpBuilder.build] (dense):
 * same column entries, same float solve, same exact certified bound, same Neumaier–Shcherbina safe
 * bound. This guards the #602 sparse-primary pipeline's representation — an unsound or lossy CSC build
 * would silently weaken or break the over-cap bound.
 */
class SparseLpModelTest {

    private fun columnEntries(model: LpModel, j: Int): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        model.forEachInColumn(j) { row, v -> out[row] = v }
        return out
    }

    @Test
    fun sparse_columns_match_the_dense_matrix() {
        val rng = Random(1)
        repeat(300) {
            val b = randomBuilder(rng)
            val dense = b.build(Sense.MINIMIZE)
            val sparse = b.buildSparse(Sense.MINIMIZE)
            assertEquals(dense.n, sparse.n)
            assertEquals(dense.m, sparse.m)
            assertTrue(sparse.a == null, "sparse model must not allocate the dense matrix")
            assertTrue(dense.rhs.contentEquals(sparse.rhs))
            assertTrue(dense.cost.contentEquals(sparse.cost))
            assertTrue(dense.upper.contentEquals(sparse.upper))
            assertTrue(dense.hasUpper.contentEquals(sparse.hasUpper))
            assertEquals(dense.objConstant, sparse.objConstant)
            for (j in 0 until dense.n) {
                assertEquals(columnEntries(dense, j), columnEntries(sparse, j), "column $j entries")
            }
        }
    }

    @Test
    fun repeated_columns_within_a_row_are_summed_in_the_csc() {
        val b = LpBuilder()
        val x = b.addVar(0L, 5L)
        val y = b.addVar(0L, 5L)
        // 2x + 3x + y <= 10  ⇒  the CSC entry for x must be the summed 5.
        b.addRow(intArrayOf(x, x, y), longArrayOf(2L, 3L, 1L), Relation.LE, 10L)
        val sparse = b.buildSparse(Sense.MINIMIZE)
        assertEquals(mapOf(0 to 5L), columnEntries(sparse, x))
        assertEquals(mapOf(0 to 1L), columnEntries(sparse, y))
    }

    @Test
    fun sparse_and_dense_certify_the_same_bound() {
        val rng = Random(7)
        var solved = 0
        repeat(400) {
            val b = randomBuilder(rng)
            val dense = b.build(Sense.MINIMIZE)
            val sparse = b.buildSparse(Sense.MINIMIZE)
            val rd = RevisedSimplex(dense).solve()
            val rs = RevisedSimplex(sparse).solve()
            assertEquals(rd == null, rs == null, "convergence must agree")
            if (rd == null || rs == null) return@repeat
            solved++
            assertTrue(abs(rd.objective - rs.objective) < 1e-6, "float objective ${rd.objective} vs ${rs.objective}")
            // Exact certified bound: identical model ⇒ identical certificate from either representation.
            assertEquals(
                ExactBasisCertifier.lowerBoundCeil(dense, rd.basis),
                ExactBasisCertifier.lowerBoundCeil(sparse, rs.basis),
                "certified bound",
            )
            // Cheap safe bound through the column accessor must match too.
            assertEquals(
                safeObjectiveLowerBound(dense, rd.duals),
                safeObjectiveLowerBound(sparse, rs.duals),
                "safe bound",
            )
        }
        assertTrue(solved > 50, "expected a meaningful number of solved instances, got $solved")
    }

    /** A small random bounded-variable LP with a linear objective; rows are sparse `<=`/`>=`/`=`. */
    private fun randomBuilder(rng: Random): LpBuilder {
        val b = LpBuilder()
        val n = 2 + rng.nextInt(5)
        repeat(n) {
            val lo = rng.nextInt(-3, 3).toLong()
            val hi = lo + rng.nextInt(1, 6)
            b.addVar(lo, hi, cost = rng.nextInt(-4, 5).toLong())
        }
        val m = 1 + rng.nextInt(5)
        repeat(m) {
            val k = 1 + rng.nextInt(n)
            val cols = (0 until n).shuffled(rng).take(k).toIntArray()
            val vals = LongArray(k) { rng.nextInt(-3, 4).toLong() }
            val rel = when (rng.nextInt(3)) {
                0 -> Relation.LE
                1 -> Relation.GE
                else -> Relation.EQ
            }
            b.addRow(cols, vals, rel, rng.nextInt(-5, 10).toLong())
        }
        return b
    }
}
