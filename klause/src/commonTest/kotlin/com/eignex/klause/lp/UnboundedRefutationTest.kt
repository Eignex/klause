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
    fun `refutes a cycle whose multipliers are not all one`() {
        // 3·(x0 − x1 ≤ −1) + 2·(x1 − x2 ≤ −2) + 6·(x2 − x0 ≤ 0) does not cancel termwise; the refutation
        // needs the scaled combination, so the certificate rests on multipliers the basis has to supply
        // exactly rather than on a sum of unit rows.
        val rows = listOf(
            Linear(intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.LE, -1),
            Linear(intArrayOf(3, -3), intArrayOf(1, 2), LinearOp.LE, -2),
            Linear(intArrayOf(6, -6), intArrayOf(2, 0), LinearOp.LE, 1),
        )
        assertTrue(unboundedlyInfeasible(open(3), rows))
    }

    @Test
    fun `refutes an equality whose coefficients cannot reach its right-hand side`() {
        // 3x + 3y = 1: every value the left side takes is a multiple of 3, so no integer solution exists
        // however large x and y grow. The relaxation is feasible and no interval closes, which leaves the
        // coefficients themselves as the only thing that can refute it.
        val rows = listOf(Linear(intArrayOf(3, 3), intArrayOf(0, 1), LinearOp.EQ, 1))
        assertTrue(unboundedlyInfeasible(open(2), rows))
    }

    @Test
    fun `stays silent when the coefficients do reach the right-hand side`() {
        val rows = listOf(Linear(intArrayOf(3, 3), intArrayOf(0, 1), LinearOp.EQ, 6))
        assertFalse(unboundedlyInfeasible(open(2), rows))
    }

    @Test
    fun `refutes a divisibility contradiction that only substitution exposes`() {
        // y = 2x makes the second row 6x = 1, which no integer x satisfies. In the original coefficients
        // that row reads 3y = 1 with y free, so the contradiction appears only after eliminating y.
        val rows = listOf(
            Linear(intArrayOf(1, -2), intArrayOf(1, 0), LinearOp.EQ, 0),
            Linear(intArrayOf(3), intArrayOf(1), LinearOp.EQ, 1),
        )
        assertTrue(unboundedlyInfeasible(open(2), rows))
    }

    @Test
    fun `refutes a chain by substituting away the variables it defines`() {
        // y = 4x, z = 4y, z <= x with x >= 2^62. Interval propagation alone diverges here — the bound
        // grows by 16 per round and never closes — but eliminating the two defined variables leaves the
        // single row 15x <= 0, which the lower bound on x contradicts outright.
        val rows = listOf(
            Linear(longArrayOf(1, -4), intArrayOf(1, 0), LinearOp.EQ, 0L),
            Linear(longArrayOf(1, -4), intArrayOf(2, 1), LinearOp.EQ, 0L),
            Linear(longArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, 0L),
        )
        val bounds = arrayOf(
            OpenIntBounds(1L shl 62, null),
            OpenIntBounds(null, null),
            OpenIntBounds(null, null),
        )
        assertTrue(unboundedlyInfeasible(bounds, rows))
    }

    @Test
    fun `stays silent on the same chain without the contradicting row`() {
        val rows = listOf(
            Linear(longArrayOf(1, -4), intArrayOf(1, 0), LinearOp.EQ, 0L),
            Linear(longArrayOf(1, -4), intArrayOf(2, 1), LinearOp.EQ, 0L),
        )
        val bounds = arrayOf(
            OpenIntBounds(1L shl 62, null),
            OpenIntBounds(null, null),
            OpenIntBounds(null, null),
        )
        assertFalse(unboundedlyInfeasible(bounds, rows))
    }

    @Test
    fun `keeps a bounded variable rather than substituting it away`() {
        // z is defined by an equality but carries its own upper bound, so eliminating it would discard
        // what that bound states. The system is satisfiable (x = 0, z = 0) and must not be refuted.
        val rows = listOf(
            Linear(longArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 0L),
            Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, 0L),
        )
        val bounds = arrayOf(OpenIntBounds(null, null), OpenIntBounds(null, 5L))
        assertFalse(unboundedlyInfeasible(bounds, rows))
    }

    @Test
    fun `refutes a chain whose forced values leave the 64-bit range`() {
        // b = 8a, c = 8b, d = 8c, e = 8d with a >= 2^60 forces e past 2^72, contradicting e < 0. The
        // relaxation cannot be built at those magnitudes, so this is the exact pass's to refute.
        val big = 1L shl 60
        val rows = listOf(
            Linear(longArrayOf(1, -8), intArrayOf(1, 0), LinearOp.EQ, 0L),
            Linear(longArrayOf(1, -8), intArrayOf(2, 1), LinearOp.EQ, 0L),
            Linear(longArrayOf(1, -8), intArrayOf(3, 2), LinearOp.EQ, 0L),
            Linear(longArrayOf(1, -8), intArrayOf(4, 3), LinearOp.EQ, 0L),
        )
        val bounds = arrayOf(
            OpenIntBounds(big, null),
            OpenIntBounds(null, null),
            OpenIntBounds(null, null),
            OpenIntBounds(null, null),
            OpenIntBounds(null, -1L),
        )
        assertTrue(unboundedlyInfeasible(bounds, rows))
    }

    @Test
    fun `stays silent on a chain that is satisfiable at large values`() {
        // The same chain without the contradicting upper bound has solutions; the exact pass must not
        // mistake "the values are huge" for "there are none".
        val big = 1L shl 60
        val rows = listOf(
            Linear(longArrayOf(1, -8), intArrayOf(1, 0), LinearOp.EQ, 0L),
            Linear(longArrayOf(1, -8), intArrayOf(2, 1), LinearOp.EQ, 0L),
        )
        val bounds = arrayOf(OpenIntBounds(big, null), OpenIntBounds(null, null), OpenIntBounds(null, null))
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
