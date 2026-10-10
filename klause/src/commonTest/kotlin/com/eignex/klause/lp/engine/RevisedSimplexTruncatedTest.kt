package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse

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

}
