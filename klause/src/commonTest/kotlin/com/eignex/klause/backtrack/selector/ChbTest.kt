package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
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
    fun `cascade conflict prioritizes its implicated variable`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()))
        val chb = Chb()
        chb.pick(session, rng)

        chb.onConflict(null, BranchingConflict(intArrayOf(2)))

        assertEquals(VarRef.Bool(2), chb.pick(session, rng))
    }

    @Test
    fun `attributed failed decision receives one conflict reward`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()))
        val chb = Chb()
        chb.pick(session, rng)
        chb.onCommit(VarRef.Bool(2))

        chb.onConflict(VarRef.Bool(0), PropagationResult.Unsat(conflictBools = intArrayOf(0, 2)))

        assertEquals(VarRef.Bool(2), chb.pick(session, rng))
    }

    @Test
    fun `chb branches first on the variable most recently in a conflict`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = arrayOf<IntDomain>(),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        val chb = Chb()
        // No conflicts yet: all scores are 0, so the tie breaks to the lowest var id.
        assertEquals(VarRef.Bool(0), chb.pick(session, rng))
        // A conflict implicating var 2 lifts its Q above every untouched variable.
        chb.onConflict(VarRef.Bool(2), PropagationResult.Unsat(conflictBools = intArrayOf(2)))
        assertEquals(VarRef.Bool(2), chb.pick(session, rng))
    }
}
