package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Re-solving a bound-only revision of the model on the same engine.
 *
 * A search node differs from its parent in column bounds alone, and neither the basis matrix nor the
 * reduced costs read a bound — so the parent's factorization is still valid and re-solving should not
 * rebuild it. That saving is the whole point: the factorization is the expensive half of a node solve.
 */
class RevisedSimplexResolveBoundsTest {

    /** `x + y >= 3` over `[0, 10]²`, minimising `x + 2y`. */
    private fun base(): LpModel {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = 1L)
        val y = b.addVar(0L, 10L, cost = 2L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a bound-only revision re-solves without rebuilding the factorization`() {
        val model = base()
        val simplex = RevisedSimplex(model)
        assertNotNull(simplex.solve(null))

        assertTrue(simplex.rebind(model.rebind(longArrayOf(2L, 0L), longArrayOf(10L, 10L)), Cancellation.Never))
        val again = assertNotNull(simplex.resolveBounds())

        assertEquals(0, again.refactorizations, "the kept factorization must carry the re-solve")
    }

    @Test
    fun `a re-solved bound revision agrees with solving it cold`() {
        val lo = longArrayOf(2L, 1L)
        val hi = longArrayOf(10L, 10L)
        val model = base()
        val simplex = RevisedSimplex(model)
        assertNotNull(simplex.solve(null))

        assertTrue(simplex.rebind(model.rebind(lo, hi), Cancellation.Never))
        val reused = assertNotNull(simplex.resolveBounds())
        val cold = assertNotNull(RevisedSimplex(base().rebind(lo, hi)).solve(null))

        assertEquals(cold.objective, reused.objective, 1e-9, "reuse changes the pivot path, not the optimum")
    }

    @Test
    fun `a model with a different matrix is refused rather than reused`() {
        val simplex = RevisedSimplex(base())
        assertNotNull(simplex.solve(null))

        // A second build is an equal model over its own arrays, which is exactly what a rebuilt or
        // cut-augmented relaxation is — reusing a factorization across it would be unsound.
        assertFalse(
            simplex.rebind(base(), Cancellation.Never),
            "only a shared matrix and objective may reuse the factorization",
        )
    }
}
