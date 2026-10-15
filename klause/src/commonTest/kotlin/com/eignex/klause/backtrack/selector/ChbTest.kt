package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ChbTest {

    private val rng = Random(1)

    @Test
    fun `a cascade conflict should prioritize its implicated variable`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()))
        val chb = Chb()
        chb.pick(session, rng)

        chb.onConflict(null, BranchingConflict(intArrayOf(2)))

        assertEquals(VarRef.Bool(2), chb.pick(session, rng))
    }

    @Test
    fun `an attributed failed decision should receive one conflict reward`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()))
        val chb = Chb()
        chb.pick(session, rng)
        chb.onCommit(VarRef.Bool(2))

        chb.onConflict(VarRef.Bool(0), PropagationResult.Unsat(conflictBools = intArrayOf(0, 2)))

        assertEquals(VarRef.Bool(2), chb.pick(session, rng))
    }
}
