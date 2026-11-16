package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SearchStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootProbingGeneralAdmissionTest {
    @Test
    fun `General admission should finish its initial global batch before restricting probes`() {
        for ((native, gate) in listOf(false to true, false to false, true to true)) {
            val session = PropagationSession(rootProblem(intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val stats = SearchStatsSink()
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 2,
                rootProbeImplications = false, rootProbeGeneralGate = gate)

            pass.run(session, params, stats)
            assertEquals(2L, stats.inprocessProbes)
            pass.releaseRetainedBuffers()
            pass.run(session, params, stats)

            assertEquals(if (!native && gate) 2L else 4L, stats.inprocessProbes)
            assertEquals(0, session.decisionLevel)
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `a new General learned neighborhood should prove a root fact after exploratory probing`() {
        val source = Problem(4, 0, emptyArray(), arrayOf<Factor>(
            PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(2, 4, 6), PbOp.LE, 1),
            PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(3, 4, 6), PbOp.GE, 2),
        ))
        val session = PropagationSession(source.bake(), nativeSat = false)
        val pass = RootProbingPass()
        val params = BacktrackParams(rootProbeBatch = 1, rootProbeImplications = false,
            rootProbeGeneralGate = true, inprocessingProbeInterval = 10000L)
        val stats = SearchStatsSink()
        for (variable in 0..3) assertNull(session.boolValue(variable))
        assertTrue(pass.optionalWorkDue(0L, params))
        pass.run(session, params, stats)
        val work = session.work
        pass.run(session, params, stats)
        assertEquals(work, session.work)
        assertEquals(1L, stats.inprocessProbes)
        assertFalse(pass.optionalWorkDue(9999L, params))

        session.addLearnedClause(Clause(intArrayOf(3, 4, 6)), 3)
        assertTrue(pass.optionalWorkDue(10000L, params))
        pass.run(session, params, stats)

        assertEquals(2L, stats.inprocessProbes)
        assertEquals(false, session.boolValue(1))
        assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
        assertFalse(pass.optionalWorkDue(19999L, params))
        assertTrue(pass.optionalWorkDue(20000L, params))
    }

    @Test
    fun `an empty General neighborhood should retain its productive opposite through an unfunded slice`() {
        for (releaseBuffers in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf<Factor>(
                PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(2, 4, 6), PbOp.LE, 1),
                PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(3, 4, 6), PbOp.GE, 2),
            ))
            val session = PropagationSession(source.bake(), nativeSat = false)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 1, rootProbeImplications = false,
                rootProbeGeneralGate = true)
            val stats = SearchStatsSink()
            pass.run(session, params, stats)
            session.addLearnedClause(Clause(intArrayOf(2, 4, 6)), 3)
            pass.run(session, params, stats)
            assertNull(session.boolValue(1))
            assertEquals(2L, stats.inprocessProbes)

            pass.run(session, params, stats, containingWork = 1L)
            if (releaseBuffers) pass.releaseRetainedBuffers()
            pass.run(session, params, stats)

            assertEquals(false, session.boolValue(1))
            assertEquals(3L, stats.inprocessProbes)
            assertEquals(0, session.decisionLevel)
            assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
        }
    }

    @Test
    fun `two empty General polarities should leave a later learned literal reachable`() {
        val source = Problem(4, 0, emptyArray(), arrayOf<Factor>(
            PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(2, 4, 6), PbOp.LE, 1),
            PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(3, 4, 6), PbOp.GE, 2),
        ))
        val session = PropagationSession(source.bake(), nativeSat = false)
        val pass = RootProbingPass()
        val params = BacktrackParams(rootProbeBatch = 1, rootProbeImplications = false,
            rootProbeGeneralGate = true)
        pass.run(session, params, null)
        session.addLearnedClause(Clause(intArrayOf(0, 2, 4, 6)), 4)

        repeat(5) { pass.run(session, params, null) }

        assertEquals(false, session.boolValue(1))
        assertEquals(0, session.decisionLevel)
        assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
    }

    @Test
    fun `completed General probes should report speculative pins before rollback`() {
        for (propagates in listOf(false, true)) {
            val factor = if (propagates) {
                PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(0, 2, 4), PbOp.LE, 1)
            } else {
                Clause(intArrayOf(0, 2, 4))
            }
            val session = PropagationSession(Problem(3, 0, emptyArray(), arrayOf<Factor>(factor)).bake(),
                nativeSat = false)
            val stats = SearchStatsSink()

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1,
                rootProbeImplications = false, rootProbeGeneralGate = true), stats)

            val learning = stats.snapshot().learning
            assertEquals(if (propagates) 2L else 0L, learning["inprocessProbeGeneralPropagatedPins"] ?: 0L)
            val outcome = if (propagates) "inprocessProbeGeneralProductive" else "inprocessProbeGeneralEmpty"
            assertEquals(1L, learning.getValue(outcome))
            for (variable in 0..2) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `General admission should preserve useful initial probing without binary hints`() {
        val source = Problem(3, 0, emptyArray(), arrayOf<Factor>(
            PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(0, 2, 4), PbOp.LE, 1),
            PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(1, 2, 4), PbOp.GE, 2),
        ))
        val session = PropagationSession(source.bake(), nativeSat = false)
        val stats = SearchStatsSink()

        RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1,
            rootProbeImplications = false, rootProbeGeneralGate = true), stats)

        assertEquals(1L, stats.inprocessProbes)
        assertEquals(false, session.boolValue(0))
        assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
        assertEquals(0, session.decisionLevel)
    }

    @Test
    fun `General admission should retain its exploratory batch when a slice is unfunded`() {
        val session = PropagationSession(rootProblem(intArrayOf(0, 2, 4)).bake(), nativeSat = false)
        val stats = SearchStatsSink()
        val pass = RootProbingPass()
        val params = BacktrackParams(rootProbeBatch = 1, rootProbeImplications = false, rootProbeGeneralGate = true)

        pass.run(session, params, stats, containingWork = 0L)
        assertEquals(0L, stats.inprocessProbes)
        pass.run(session, params, stats)

        assertEquals(1L, stats.inprocessProbes)
        assertEquals(0, session.decisionLevel)
    }

    @Test
    fun `scope reset should renew General exploratory probing`() {
        val session = PropagationSession(rootProblem(intArrayOf(0, 2, 4)).bake(), nativeSat = false)
        val stats = SearchStatsSink()
        val pass = RootProbingPass()
        val params = BacktrackParams(rootProbeBatch = 1, rootProbeImplications = false, rootProbeGeneralGate = true)
        pass.run(session, params, stats)
        pass.run(session, params, stats)
        assertEquals(1L, stats.inprocessProbes)

        pass.reset()
        pass.run(session, params, stats)

        assertEquals(2L, stats.inprocessProbes)
        assertEquals(0, session.decisionLevel)
    }

    @Test
    fun `General admission should preserve probing with source binaries or root facts`() {
        for (clauses in listOf(
            arrayOf(intArrayOf(0, 2)),
            arrayOf(intArrayOf(1), intArrayOf(0, 2, 4)),
        )) {
            val session = PropagationSession(rootProblem(*clauses).bake(), nativeSat = false)
            val stats = SearchStatsSink()

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1,
                rootProbeImplications = false, rootProbeGeneralGate = true), stats)

            assertEquals(1L, stats.inprocessProbes)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `General admission should preserve integer endpoint probing`() {
        for (gate in listOf(false, true)) {
            val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))
            val session = PropagationSession(source.bake())

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 4, rootProbeGeneralGate = gate), null)

            assertEquals(1L, session.intDomain(0).min)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `General admission should publish a retained implication after optional buffer release`() {
        val source = Problem(3, 0, emptyArray(), arrayOf<Factor>(
            PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(0, 2, 4), PbOp.LE, 1),
        ))
        val session = PropagationSession(source.bake(), nativeSat = false)
        val pass = RootProbingPass()
        val params = BacktrackParams(rootProbeBatch = 1, inprocessingProbeWork = 256L)
        val stats = SearchStatsSink()
        pass.run(session, params, stats, containingWork = 87L)
        assertTrue(pass.hasContinuation(session), stats.snapshot().learning.toString())
        assertEquals(0, session.learnedClauseCount)

        pass.releaseRetainedBuffers()
        pass.continueAtRoot(session, params.copy(rootProbeGeneralGate = true), stats, containingWork = 1024L)

        assertFalse(pass.hasContinuation(session))
        assertEquals(1L, stats.inprocessProbes)
        assertEquals(1, session.learnedClauseCount)
        assertEquals(0, session.decisionLevel)
        for (variable in 0..2) assertNull(session.boolValue(variable))
    }
}
