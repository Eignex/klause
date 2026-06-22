package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.table.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Element-constraint structural reduction ([Presolve.reduceElement]). Each test asserts the global is
 * rewritten into the equality its fixed structure implies, or left untouched when nothing pins it.
 */
class ElementReductionTest {

    private fun theLinear(problem: Problem): Linear = problem.factors.filterIsInstance<Linear>().single()

    @Test
    fun `a fixed index into a constant array becomes a result equality`() {
        // idx = 2 (offset 1) selects arr[1] = 20, so result = 20.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(2, 2), IntDomain(0, 100)),
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(10, 20, 30), arrIsVars = false)),
        )
        val out = Presolve.reduceElement(problem)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(listOf(1), eq.vars.toList())
        assertEquals(20, eq.bound)
    }

    @Test
    fun `a fixed index into a variable array becomes an equality between result and the selected var`() {
        // idx = 1 (offset 1) selects arr[0] = var 2, so result (var 1) = var 2.
        val problem = Problem(
            0,
            5,
            Array(5) { IntDomain(0, 9) }.also { it[0] = IntDomain(1, 1) },
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(2, 3, 4), arrIsVars = true)),
        )
        val out = Presolve.reduceElement(problem)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(0, eq.bound)
        assertEquals(setOf(1, 2), eq.vars.toSet())
    }

    @Test
    fun `a fixed index selecting the result variable itself drops the element`() {
        // arr[0] is var 1 = result, so the constraint is result = result — vacuous.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 9) }.also { it[0] = IntDomain(1, 1) },
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(1, 2), arrIsVars = true)),
        )
        val out = Presolve.reduceElement(problem)
        assertTrue(out.factors.isEmpty(), "the vacuous element drops with no replacement")
    }

    @Test
    fun `a constant array of one value fixes the result and tightens the index range`() {
        // Every entry is 7, so result = 7; dropping the element keeps idx in its valid range [1, 3].
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 10)),
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(7, 7, 7), arrIsVars = false)),
        )
        val out = Presolve.reduceElement(problem)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        assertEquals(7, theLinear(out).bound)
        assertEquals(1, out.intDomains[0].min, "index lower bound clamped to the array's first position")
        assertEquals(3, out.intDomains[0].max, "index upper bound clamped to the array's last position")
    }

    @Test
    fun `an unconstrained element is left untouched`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 3), IntDomain(0, 100)),
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(10, 20, 30), arrIsVars = false)),
        )
        assertSame(problem, Presolve.reduceElement(problem), "no fixed index and a varied array is the no-op signal")
    }
}
