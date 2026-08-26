package com.eignex.klause.propagation.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Appending the joint difference system to a presolved problem. */
class PostDifferenceSystemTest {

    private fun problemOf(vararg factors: Factor) = Problem(
        numBoolVars = 4,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 10) },
        factors = arrayOf(*factors),
    )

    private fun reified(aux: Int, hi: Int, lo: Int, bound: Long) =
        ReifiedLinear(aux, longArrayOf(1, -1), intArrayOf(hi, lo), LinearOp.LE, bound)

    @Test
    fun `a model with reified difference rows gains one system factor`() {
        val problem = problemOf(reified(0, 1, 0, -1L), reified(1, 2, 1, -1L))
        val posted = problem.withDifferenceSystem()
        assertEquals(problem.factors.size + 1, posted.factors.size)
        assertTrue(posted.factors.last() is DifferenceSystem)
    }

    @Test
    fun `the rows the system reads stay posted`() {
        val problem = problemOf(reified(0, 1, 0, -1L), reified(1, 2, 1, -1L))
        val posted = problem.withDifferenceSystem()
        assertEquals(2, posted.factors.count { it is ReifiedLinear }, "the system is redundant with them")
    }

    @Test
    fun `a model with only unconditional differences is left alone`() {
        // Those rows already propagate exactly on their own; a system over them repeats their work.
        val problem = problemOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3))
        assertSame(problem, problem.withDifferenceSystem())
    }

    @Test
    fun `a model whose clamped columns make the system unusable is left alone`() {
        // An unbounded model invents a ±2^62 range per open column and posts it as a declared range.
        // A potential cannot be summed over weights that size, so the propagator would decline on every
        // fire — 320,000 times on one nec-smt instance — and the factor is pure cost.
        val clamp = 1L shl 62
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(-clamp, clamp) },
            factors = arrayOf<Factor>(reified(0, 1, 0, -1L), reified(1, 2, 1, -1L)),
        )
        assertSame(problem, problem.withDifferenceSystem())
    }

    @Test
    fun `a model with no difference rows is left alone`() {
        val problem = problemOf(Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3))
        assertSame(problem, problem.withDifferenceSystem())
    }
}
