package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrivateRebuildFreeSelectorTest {
    @Test
    fun `preferred free coordinates should precede other live columns`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(intArrayOf(0, 2, 4), 1)))
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.seed(Assumptions.None))
        val selector = PrivateRebuildFreeSelector(intArrayOf(2))

        val selected = selector.pick(session, Random(1))

        assertEquals(VarRef.Bool(2), selected)
    }

    @Test
    fun `a fixed preference should leave the remaining exhaustive decisions available`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(intArrayOf(0, 2, 4), 1)))
        val session = PropagationSession(problem)
        session.pinBool(2, false)
        val selector = PrivateRebuildFreeSelector(intArrayOf(2))

        val selected = selector.pick(session, Random(1))

        assertEquals(VarRef.Bool(0), selected)
    }

    @Test
    fun `a fresh preference should not retain another sessions assigned columns`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(intArrayOf(0, 2, 4), 1)))
        val first = PropagationSession(problem)
        val second = PropagationSession(problem)
        first.pinBool(2, false)
        val selector = PrivateRebuildFreeSelector(intArrayOf(2))
        selector.pick(first, Random(1))

        val selected = selector.fresh().pick(second, Random(1))

        assertEquals(VarRef.Bool(2), selected)
    }

    @Test
    fun `conflicts should prioritize implicated free coordinates`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(Xor(intArrayOf(0, 2, 4, 6), 1)))
        val session = PropagationSession(problem)
        val selector = PrivateRebuildFreeSelector(intArrayOf(0, 1, 2))
        val first = assertIs<VarRef.Bool>(selector.pick(session, Random(1)))
        session.pinBool(first.varId, false)
        val implicated = (0..2).last { it != first.varId }

        selector.onConflict(first, BranchingConflict(intArrayOf(implicated)))
        val selected = selector.pick(session, Random(1))

        assertEquals(VarRef.Bool(implicated), selected)
    }

    @Test
    fun `unassigned free coordinates should regain their conflict priority`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(intArrayOf(0, 2, 4), 1)))
        val session = PropagationSession(problem)
        val selector = PrivateRebuildFreeSelector(intArrayOf(0, 1))
        val first = assertIs<VarRef.Bool>(selector.pick(session, Random(1)))
        selector.onConflict(first)

        selector.onUnassign(first)
        val selected = selector.pick(session, Random(1))

        assertTrue(selector.tracksUnassign)
        assertEquals(first, selected)
    }

    @Test
    fun `fresh consumers should not inherit conflict activity`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(Xor(intArrayOf(0, 2, 4, 6), 1)))
        val session = PropagationSession(problem)
        val selector = PrivateRebuildFreeSelector(intArrayOf(0, 1, 2))
        val first = assertIs<VarRef.Bool>(selector.pick(session, Random(1)))
        val implicated = (0..2).last { it != first.varId }
        selector.onConflict(first, BranchingConflict(intArrayOf(implicated)))
        assertEquals(VarRef.Bool(implicated), selector.pick(session, Random(1)))

        val selected = selector.fresh().pick(PropagationSession(problem), Random(1))

        assertEquals(first, selected)
    }
}
