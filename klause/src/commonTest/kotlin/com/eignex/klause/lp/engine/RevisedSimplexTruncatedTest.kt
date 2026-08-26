package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a solve hands back when it stops before reaching the optimum.
 *
 * The dual simplex is dual-feasible from its first basis, so every iterate it passes through carries a
 * valid lower bound. Discarding one throws away a usable bound and leaves the node unpruned, which is
 * the whole cost of returning nothing.
 */
class RevisedSimplexTruncatedTest {

    /** A model big enough that the solve takes several pivots, so stopping it lands mid-solve. */
    private fun model(seed: Int, columns: Int = 24): LpModel {
        val rng = Random(seed)
        val b = LpBuilder()
        val cols = IntArray(columns) { b.addVar(0L, 9L, cost = rng.nextLong(1L, 6L)) }
        repeat(columns / 2) {
            val pick = IntArray(4) { k -> cols[(it * 3 + k) % columns] }
            b.addRow(pick, LongArray(4) { 1L }, Relation.GE, rng.nextLong(2L, 8L))
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a solve stopped by the budget returns its iterate rather than nothing`() {
        // Cancelled from the very first poll, so the solve cannot have reached the optimum.
        val stopped = RevisedSimplex(model(1), Cancellation { true }).solve(null)

        // Either it never got an iterate (nothing to report) or it reported one flagged non-optimal.
        if (stopped != null) {
            assertFalse(stopped.optimal, "an iterate handed back mid-solve is not an optimum")
        }
    }

    @Test
    fun `a truncated iterate bounds the true optimum from below`() {
        // The budget is consulted every 32 iterations, so the model has to be wide enough that the
        // solve is still running at the second poll — otherwise it finishes before anything can stop it.
        var polls = 0
        val stopAtSecondPoll = Cancellation { ++polls >= 2 }
        val truncated = assertNotNull(
            RevisedSimplex(model(7, columns = 160), stopAtSecondPoll).solve(null),
            "the solve should have been stopped mid-flight, with an iterate to show for it",
        )
        val optimum = assertNotNull(RevisedSimplex(model(7, columns = 160)).solve(null))

        assertFalse(truncated.optimal, "a stopped solve is not an optimum")
        assertTrue(
            truncated.objective <= optimum.objective + 1e-6,
            "a dual-feasible iterate must not claim more than the optimum: " +
                "${truncated.objective} against ${optimum.objective}",
        )
    }

    @Test
    fun `a completed solve is still reported as optimal`() {
        val done = assertNotNull(RevisedSimplex(model(3)).solve(null))

        assertTrue(done.optimal, "an uninterrupted solve reaches the optimum")
        assertEquals(1, done.refactorizations, "a cold solve factorizes its slack basis once")
    }
}
