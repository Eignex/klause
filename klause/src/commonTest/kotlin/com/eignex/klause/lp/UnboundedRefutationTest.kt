package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Refuting a system over genuinely open domains. The refuting direction is the only usable one — a
 * `false` means "no conclusion", never "satisfiable" — so the tests that matter most are the ones
 * asserting it stays silent.
 */
class UnboundedRefutationTest {

    private fun open(n: Int) = Array(n) { OpenIntBounds(null, null) }

    @Test
    fun `refutes a difference cycle over unbounded variables`() {
        // x0 - x1 <= -1 and x1 - x0 <= -1 sum to 0 <= -2: infeasible over the reals, with no bound on
        // either variable playing any part.
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, -1),
        )
        assertTrue(unboundedlyInfeasible(open(2), rows))
    }

    @Test
    fun `stays silent on a satisfiable system`() {
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 2),
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 1),
        )
        assertFalse(unboundedlyInfeasible(open(2), rows))
    }

    @Test
    fun `stays silent when every direction is unbounded`() {
        // An absolutely unbounded system always has an integer solution, so it is never refutable.
        val rows = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5))
        assertFalse(unboundedlyInfeasible(open(2), rows))
    }

    @Test
    fun `stays silent when nothing is open`() {
        // A fully declared model is already decided by the ordinary search over its real domains.
        val rows = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, -1))
        val bounds = arrayOf(OpenIntBounds(0L, 10L), OpenIntBounds(0L, 10L))
        assertFalse(unboundedlyInfeasible(bounds, rows))
    }

    @Test
    fun `refutes with an unbounded row present`() {
        // The refutation lives in the two-sided pair; a third row whose direction is unbounded must
        // neither hide it nor be needed for it.
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, -1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 7),
        )
        assertTrue(unboundedlyInfeasible(open(2), rows))
    }
}
