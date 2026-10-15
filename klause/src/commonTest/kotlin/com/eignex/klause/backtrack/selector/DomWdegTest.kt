package com.eignex.klause.backtrack.selector

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DomWdegTest {

    @Test
    fun `dom-wdeg should branch a column whose span exceeds a Long`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)

        assertEquals(VarRef.IntVar(0), DomWdeg().pick(session, Random(1)))
    }

    @Test
    fun `conflict weights should change selection and survive restart on a shared projection`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 1), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0), AllDifferent(intArrayOf(1, 2), 0, 3)),
        )
        val projection = PropagationProblem(problem)
        val session = PropagationSession(projection)
        val peer = PropagationSession(projection)
        val selector = DomWdeg()
        assertEquals(VarRef.IntVar(0), selector.pick(session, Random(1)))
        val conflict = assertIs<PropagationResult.Unsat>(
            problem.propagate(Assumptions(ints = mapOf(1 to 1L, 2 to 1L))),
        )
        assertTrue(1 in conflict.conflictFactors)
        selector.onConflict(VarRef.IntVar(2), conflict)
        session.seed(Assumptions.None)
        selector.onRestart()

        assertEquals(VarRef.IntVar(1), selector.pick(session, Random(1)))
        assertEquals(VarRef.IntVar(0), selector.fresh().pick(peer, Random(1)))
    }

    @Test
    fun `conflicts received before the first pick should count once toward weighted degree`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 2)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 0),
            ),
        )
        val session = PropagationSession(PropagationProblem(problem))
        val selector = DomWdeg()
        selector.onConflict(VarRef.IntVar(1), PropagationResult.Unsat(conflictFactors = intArrayOf(1)))

        assertEquals(VarRef.IntVar(1), selector.pick(session, Random(1)))
        assertEquals(VarRef.IntVar(1), selector.pick(session, Random(1)))
        assertEquals(VarRef.IntVar(0), selector.fresh().pick(session, Random(1)))
    }
}
