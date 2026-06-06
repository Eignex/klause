package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class HeuristicsTest {

    private val rng = Random(1)

    @Test
    fun `smallest lower bound prefers the int with the lowest minimum`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(2, 9), IntDomain(-3, 9)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        // Free bool counts as minimum 0; int 1's minimum of -3 undercuts it.
        assertEquals(VarRef.IntVar(1), SmallestLowerBound.pick(session, rng))
    }

    @Test
    fun `smallest lower bound counts free bools as zero`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(2, 9)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.Bool(0), SmallestLowerBound.pick(session, rng))
    }

    @Test
    fun `largest upper bound prefers the int with the highest maximum`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 7)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.IntVar(1), LargestUpperBound.pick(session, rng))
    }

    @Test
    fun `indomain split yields the interval midpoint first`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        val values = IndomainSplit.values(session, VarRef.IntVar(0), rng).toList()
        assertEquals(5, values.first())
        // The trailing walk completes the domain without repeating the midpoint.
        assertEquals((0..10).toList().sorted(), values.sorted())
    }

    @Test
    fun `indomain split midpoint respects a shifted interval`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(10, 13)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(11, IndomainSplit.values(session, VarRef.IntVar(0), rng).first())
    }
}
