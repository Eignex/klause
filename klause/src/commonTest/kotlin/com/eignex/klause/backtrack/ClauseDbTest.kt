package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClauseDbTest {

    // Implication chain a -> b -> c, so a learned (!a | !b | c) vivifies to (!a | c): probing a=true
    // propagates b and c, falsifying !b (dropped) and satisfying c (prefix proven implied).
    private fun chainProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
        ),
    )

    private fun vivifiedLiterals(nativeSat: Boolean): Set<Int> {
        val baked = chainProblem().bake()
        val session = PropagationSession(baked, nativeSat = nativeSat)
        assertEquals(nativeSat, session.usesNativeSat)
        session.addLearnedClause(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            lbd = 3,
        )
        vivify(session, BacktrackParams(vivification = true, vivifyBatch = 8), 0)
        assertEquals(1, session.learnedClauseCount)
        return session.learnedClauseLiterals(0).toSet()
    }

    @Test
    fun `vivify should strengthen a learned clause on the general lane`() {
        assertEquals(setOf(Lit.make(0, false), Lit.make(2, true)), vivifiedLiterals(nativeSat = false))
    }

    @Test
    fun `vivify should strengthen a learned clause in the native-SAT arena store`() {
        assertEquals(setOf(Lit.make(0, false), Lit.make(2, true)), vivifiedLiterals(nativeSat = true))
    }

    @Test
    fun `a vivified import should keep the arm it was imported from`() {
        val session = PropagationSession(chainProblem().bake())
        session.addLearnedClause(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            lbd = 3,
            origin = 2,
        )

        vivify(session, BacktrackParams(vivification = true, vivifyBatch = 8), 0)

        assertEquals(2, session.learnedClauseLiterals(0).size)
        assertEquals(2, session.learnedClauseOrigin(0))
    }

    @Test
    fun `a vivified clause should inherit its parent's LBD`() {
        val baked = chainProblem().bake()
        val session = PropagationSession(baked)
        session.addLearnedClause(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            lbd = 1,
        )
        vivify(session, BacktrackParams(vivification = true, vivifyBatch = 8), 0)
        assertEquals(2, session.learnedClauseLiterals(0).size)
        assertEquals(1, session.learnedClauseLbd(0))
    }

    @Test
    fun `reset should release grown vivification metadata and unattached rows`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            repeat(9) { session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 3) }
            val pass = VivificationPass()
            val empty = SharingMemoryObservation(1024L, 128, { false }, {})
            val baseline = empty.snapshot { pass.retainedMemory(empty, false) }
            val stats = SearchStatsSink()
            var prepared = false
            val cancellation = Cancellation {
                prepared = prepared || (stats.inprocessProbes >= 9L && session.decisionLevel > 0)
                prepared && session.decisionLevel == 0
            }
            pass.run(session, BacktrackParams(vivifyBatch = 9, cancellation = cancellation), stats)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.reset()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            val retainedBytes = assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes")
            val releasedBytes = assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes")
            assertTrue(retainedBytes > releasedBytes)
            assertEquals(assertNotNull(baseline.completeValues).getValue("ownedPrimitiveBytes"), releasedBytes)
            assertEquals(0L, released.completeValues?.getValue("ownedReferenceSlots"))
            assertEquals(9, session.learnedClauseCount)
            assertEquals(0, session.decisionLevel)
            assertFalse(session.fixpointCancelled)
        }
    }
}
