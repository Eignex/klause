package com.eignex.klause.propagation.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostDifferenceSystemTest {

    private fun problemOf(vararg factors: Factor) = Problem(
        numBoolVars = 4,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 10) },
        factors = arrayOf(*factors),
    ).bake()

    private fun reified(aux: Int, hi: Int, lo: Int, bound: Long) =
        ReifiedLinear(aux, longArrayOf(1, -1), intArrayOf(hi, lo), LinearOp.LE, bound)

    @Test
    fun `unary guarded bounds keep their original factors without a joint system`() {
        val problem = problemOf(
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.LE, 3L),
            ReifiedLinear(1, longArrayOf(1), intArrayOf(0), LinearOp.GE, 5L),
            ReifiedLinear(2, longArrayOf(1), intArrayOf(1), LinearOp.EQ, 1L),
        )

        assertSame(problem, problem.withDifferenceSystem())
    }

    @Test
    fun `unary bounds refute an incompatible guard through their original factors`() {
        val problem = problemOf(
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.LE, 3L),
            ReifiedLinear(1, longArrayOf(1), intArrayOf(0), LinearOp.GE, 5L),
        ).withDifferenceSystem()
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(state.pinBool(0, true))

        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(3L, state.intDomains[0].max)
        assertEquals(false, state.boolValues[1])
    }

    @Test
    fun `unconditional difference rows convey unary bounds without a joint system`() {
        val problem = problemOf(
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.LE, 3L),
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0L),
            ReifiedLinear(1, longArrayOf(1), intArrayOf(1), LinearOp.GE, 5L),
        )
        val posted = problem.withDifferenceSystem()
        assertSame(problem, posted)
        val state = PropagationState(posted, Assumptions.None)
        assertTrue(state.pinBool(0, true))

        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(3L, state.intDomains[1].max)
        assertEquals(false, state.boolValues[1])
    }

    @Test
    fun `a model with reified difference rows gains one system factor and keeps the rows it reads`() {
        val problem = problemOf(reified(0, 1, 0, -1L), reified(1, 2, 1, -1L))
        val posted = problem.withDifferenceSystem()
        assertEquals(problem.factors.size + 1, posted.factors.size)
        assertTrue(posted.factors.last() is DifferenceSystem)
        assertEquals(2, posted.factors.count { it is ReifiedLinear }, "the system is redundant with them")
    }

    @Test
    fun `a model with over-heavy declared ranges keeps its guarded difference system`() {
        // The range edges are redundant with CP's domains, so the joint graph drops only those it cannot
        // represent and still refutes guarded model rows from the remaining graph.
        val clamp = 1L shl 62
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(-clamp, clamp) },
            factors = arrayOf<Factor>(reified(0, 1, 0, -1L), reified(1, 2, 1, -1L)),
        ).bake()
        assertTrue(problem.withDifferenceSystem().factors.last() is DifferenceSystem)
    }

}
