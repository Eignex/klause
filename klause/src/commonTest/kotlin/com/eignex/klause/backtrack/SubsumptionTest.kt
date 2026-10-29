package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.klause.util.SharingMemoryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsumptionTest {

    private fun problem(numBool: Int): Problem = Problem(
        numBoolVars = numBool,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(Clause(IntArray(numBool) { Lit.make(it, true) })),
    )

    private fun subsumed(nativeSat: Boolean, vararg learned: IntArray): PropagationSession {
        val baked = problem(6).bake()
        val session = PropagationSession(baked, nativeSat = nativeSat)
        assertEquals(nativeSat, session.usesNativeSat)
        for (lits in learned) session.addLearnedClause(Clause(lits), lbd = lits.size)
        subsume(session, BacktrackParams(subsumption = true, subsumeBatch = 64), 0)
        return session
    }

    private fun learnedSets(session: PropagationSession): Set<Set<Int>> =
        (0 until session.learnedClauseCount).map { session.learnedClauseLiterals(it).toSet() }.toSet()

    private fun lits(vararg spec: Pair<Int, Boolean>): IntArray =
        IntArray(spec.size) { Lit.make(spec[it].first, spec[it].second) }

    @Test
    fun `a clause subsumed by a shorter learned clause should be dropped on the general lane`() {
        val session = subsumed(
            nativeSat = false,
            lits(0 to true, 1 to false),
            lits(0 to true, 1 to false, 2 to true),
        )
        assertEquals(setOf(lits(0 to true, 1 to false).toSet()), learnedSets(session))
    }

    @Test
    fun `a clause subsumed by a shorter learned clause should be dropped in the native arena store`() {
        val session = subsumed(
            nativeSat = true,
            lits(0 to true, 1 to false),
            lits(0 to true, 1 to false, 2 to true),
        )
        assertEquals(setOf(lits(0 to true, 1 to false).toSet()), learnedSets(session))
    }

    @Test
    fun `a duplicate learned clause should be dropped`() {
        val session = subsumed(
            nativeSat = false,
            lits(0 to true, 1 to true, 2 to true),
            lits(2 to true, 1 to true, 0 to true),
        )
        assertEquals(setOf(lits(0 to true, 1 to true, 2 to true).toSet()), learnedSets(session))
    }

    @Test
    fun `self-subsuming resolution should strengthen the longer clause`() {
        // (a | !b) resolves with (a | b | c) on b: the resolvent (a | c) replaces the longer clause.
        val session = subsumed(
            nativeSat = false,
            lits(0 to true, 1 to false),
            lits(0 to true, 1 to true, 2 to true),
        )
        assertEquals(
            setOf(lits(0 to true, 1 to false).toSet(), lits(0 to true, 2 to true).toSet()),
            learnedSets(session),
        )
    }

    @Test
    fun `unrelated clauses should survive the pass untouched`() {
        val a = lits(0 to true, 1 to true, 2 to true)
        val b = lits(3 to true, 4 to true, 5 to true)
        val session = subsumed(nativeSat = false, a, b)
        assertEquals(setOf(a.toSet(), b.toSet()), learnedSets(session))
    }

    @Test
    fun `a self-subsumed resolvent should inherit its parent's LBD`() {
        val baked = problem(6).bake()
        val session = PropagationSession(baked)
        session.addLearnedClause(Clause(lits(0 to true, 1 to false)), lbd = 2)
        session.addLearnedClause(Clause(lits(0 to true, 1 to true, 2 to true)), lbd = 1)
        subsume(session, BacktrackParams(subsumption = true, subsumeBatch = 64), 0)
        val resolvent = (0 until session.learnedClauseCount)
            .single { session.learnedClauseLiterals(it).toSet() == lits(0 to true, 2 to true).toSet() }
        assertEquals(1, session.learnedClauseLbd(resolvent))
    }

    @Test
    fun `a resolvent of an imported clause should keep the arm it was imported from`() {
        val session = PropagationSession(problem(6).bake())
        session.addLearnedClause(Clause(lits(0 to true, 1 to false)), lbd = 2)
        session.addLearnedClause(Clause(lits(0 to true, 1 to true, 2 to true)), lbd = 3, origin = 4)

        subsume(session, BacktrackParams(subsumption = true, subsumeBatch = 64), 0)

        val resolvent = (0 until session.learnedClauseCount)
            .single { session.learnedClauseLiterals(it).toSet() == lits(0 to true, 2 to true).toSet() }
        assertEquals(4, session.learnedClauseOrigin(resolvent))
    }

    @Test
    fun `pressure release should discard a suspended subsumption index`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(6).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 2, 4)), 3)
            val pass = Subsumption()
            pass.run(session, BacktrackParams(inprocessingSubsumeWork = 1L), null)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.releaseRetainedBuffers()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") > 0L)
            assertEquals(0L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
            assertEquals(2, session.learnedClauseCount)
        }
    }

    @Test
    fun `tiny observation should leave suspended subsumption available to resume`() {
        val session = PropagationSession(problem(6).bake())
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
        session.addLearnedClause(Clause(intArrayOf(0, 2, 4)), 3)
        val pass = Subsumption()
        pass.run(session, BacktrackParams(inprocessingSubsumeWork = 1L), null)
        val sample = SharingMemoryObservation(1L, 1, { false }, {})

        val interrupted = sample.snapshot { pass.retainedMemory(sample, false) }
        pass.run(session, BacktrackParams(), null)

        assertEquals(SharingMemoryStatus.PARTIAL, interrupted.status)
        assertEquals(1L, interrupted.work)
        assertNull(interrupted.completeValues)
        assertEquals(setOf(setOf(0, 2)), learnedSets(session))
    }

    @Test
    fun `an obsolete subsumption index should release when no new pass is eligible`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(6).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 2, 4)), 3)
            val pass = Subsumption()
            pass.run(session, BacktrackParams(inprocessingSubsumeWork = 1L), null)

            session.forgetLearnedClauses { _, _ -> false }
            pass.run(session, BacktrackParams(inprocessingSubsumeWork = 0L), null)
            val sample = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = sample.snapshot { pass.retainedMemory(sample, false) }

            assertEquals(0, session.learnedClauseCount)
            assertEquals(0L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
        }
    }
}
