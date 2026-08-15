package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fitting a unit cube to find an integer solution without any bounds.
 *
 * The test is incomplete, so a null answer proves nothing and is never a failure in itself. What must
 * hold is the other direction: anything it *does* return has to satisfy the system, because the caller
 * takes it as a witness.
 */
class UnitCubeTestTest {

    private fun open(n: Int) = Array(n) { OpenIntBounds(null, null) }

    /** `Σ coeff·var ≤ bound`, the only shape the cube test accepts. */
    private fun le(coeffs: LongArray, vars: IntArray, bound: Long) = Linear(coeffs, vars, LinearOp.LE, bound)

    private fun satisfies(x: LongArray, rows: List<Linear>): Boolean = rows.all { f ->
        var acc = 0L
        for (k in f.vars.indices) acc += f.coeff(k) * x[f.vars[k]]
        acc <= f.bound
    }

    @Test
    fun `an unbounded system with interior yields a verified integer point`() {
        // x0 - x1 <= 10 and x1 - x0 <= 10: a wide diagonal band, unbounded in both directions.
        val rows = listOf(
            le(longArrayOf(1L, -1L), intArrayOf(0, 1), 10L),
            le(longArrayOf(-1L, 1L), intArrayOf(0, 1), 10L),
        )
        val x = assertNotNull(unitCubeSolution(open(2), rows), "a band this wide holds a unit cube")
        assertTrue(satisfies(x, rows), "the returned point must satisfy the system: ${x.toList()}")
    }

    @Test
    fun `a bounded box still yields a verified point`() {
        val rows = listOf(
            le(longArrayOf(1L), intArrayOf(0), 100L),
            le(longArrayOf(-1L), intArrayOf(0), 0L),
        )
        val bounds = arrayOf(OpenIntBounds(0L, 100L))
        val x = assertNotNull(unitCubeSolution(bounds, rows))
        assertTrue(satisfies(x, rows) && x[0] in 0L..100L)
    }

    @Test
    fun `a system too thin to hold a cube is declined rather than guessed`() {
        // 2x0 - 2x1 <= 0 and 2x1 - 2x0 <= 0 pin x0 == x1: a line, no interior.
        val rows = listOf(
            le(longArrayOf(2L, -2L), intArrayOf(0, 1), 0L),
            le(longArrayOf(-2L, 2L), intArrayOf(0, 1), 0L),
        )
        assertNull(unitCubeSolution(open(2), rows), "no cube fits in a line; the test must decline")
    }

    @Test
    fun `an infeasible system yields nothing`() {
        val rows = listOf(
            le(longArrayOf(1L), intArrayOf(0), -5L),
            le(longArrayOf(-1L), intArrayOf(0), -5L),
        )
        assertNull(unitCubeSolution(open(1), rows))
    }

    @Test
    fun `an equality is declined because a hyperplane has no interior`() {
        val rows = listOf(Linear(longArrayOf(1L, 1L), intArrayOf(0, 1), LinearOp.EQ, 4L))
        assertNull(unitCubeSolution(open(2), rows))
    }

    @Test
    fun `a returned point respects declared bounds`() {
        val rows = listOf(le(longArrayOf(1L, -1L), intArrayOf(0, 1), 10L))
        val bounds = arrayOf(OpenIntBounds(5L, 9L), OpenIntBounds(null, null))
        val x = unitCubeSolution(bounds, rows)
        if (x != null) assertTrue(x[0] in 5L..9L, "x0=${x[0]} escaped its declared bound")
    }

    @Test
    fun `an empty system yields nothing`() {
        assertNull(unitCubeSolution(open(2), emptyList()))
    }
}
