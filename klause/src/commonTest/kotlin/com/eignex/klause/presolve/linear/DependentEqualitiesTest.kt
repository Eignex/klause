package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependentEqualitiesTest {

    private fun eq(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.EQ, bound)

    private fun problem(vararg factors: Linear) = Problem(0, 3, Array(3) { IntDomain(0, 10) }, factors.toList())

    private fun dropped(p: Problem) = DependentEqualities.dropImplied(p, Cancellation.Never)

    @Test
    fun `an equality that is the sum of two others is dropped and they are kept`() {
        // x + y = 3 and y + z = 4 imply x + 2y + z = 7, which no pairwise rule sees.
        val p = problem(
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 3),
            eq(intArrayOf(1, 1), intArrayOf(1, 2), 4),
            eq(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), 7),
        )

        assertEquals(listOf(2), dropped(p).droppedIndices.toList())
    }

    @Test
    fun `an equality block with no rational solution refutes the model`() {
        // The same three rows with the sum stated as 8: the block reduces to 0 = 1.
        val p = problem(
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 3),
            eq(intArrayOf(1, 1), intArrayOf(1, 2), 4),
            eq(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), 8),
        )

        assertTrue(dropped(p).infeasible)
    }

    @Test
    fun `an inequality the equalities imply is not read`() {
        val p = problem(
            eq(intArrayOf(1, 1), intArrayOf(0, 1), 3),
            eq(intArrayOf(1, 1), intArrayOf(1, 2), 4),
            Linear(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), LinearOp.LE, 7),
        )

        assertTrue(dropped(p).isEmpty)
    }
}
