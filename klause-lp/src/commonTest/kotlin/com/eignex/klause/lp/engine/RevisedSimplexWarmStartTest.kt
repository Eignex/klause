package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a solve reports about how it started. A warm basis and a fresh factorization are separate costs:
 * reusing the basis saves pivots, and only reusing the factorization saves the `O(nnz)` LU rebuild.
 */
class RevisedSimplexWarmStartTest {

    /** `x + y >= 3`, `x <= 4`, `y <= 4`, minimising `x + 2y` — small, feasible, and not optimal at the
     *  slack start, so the solve actually pivots. */
    private fun model(): LpModel {
        val b = LpBuilder()
        val x = b.addVar(0L, 4L, cost = 1L)
        val y = b.addVar(0L, 4L, cost = 2L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a cold solve reports no warm start and one factorization`() {
        val result = assertNotNull(RevisedSimplex(model()).solve(null))

        assertFalse(result.warmStarted, "a null warm basis is a cold start")
        assertEquals(1, result.refactorizations, "the slack basis is factorized once")
    }

    @Test
    fun `a solve handed a prior basis reports the warm start and still factorizes`() {
        val cold = assertNotNull(RevisedSimplex(model()).solve(null))

        val warm = assertNotNull(RevisedSimplex(model()).solve(cold.basis))

        assertTrue(warm.warmStarted, "the prior basis was accepted")
        assertTrue(
            warm.refactorizations >= 1,
            "a warm basis still pays for its own factorization, saw ${warm.refactorizations}",
        )
        assertEquals(cold.objective, warm.objective, 1e-9, "the warm start changes only the pivot path")
    }
}
