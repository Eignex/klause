package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootProbingPassTest {
    @Test
    fun `failed literal probing should assert a consumed unconditional unit`() {
        for ((native, positive) in listOf(false to false, false to true, true to false, true to true)) {
            val literal = Lit.make(0, positive)
            val session = PropagationSession(
                rootInprocessingProblem(intArrayOf(literal, 2), intArrayOf(literal, 3)).bake(), nativeSat = native,
            )
            val stats = SearchStatsSink()

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), stats)

            assertEquals(positive, session.boolValue(0))
            assertEquals(0, session.decisionLevel)
            assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
            assertEquals(2L, stats.inprocessProbes)
            assertIs<PropagationResult.Unsat>(session.pinBool(0, !positive))
        }
    }

    @Test
    fun `consistent root probes should install a consumed conditional binary`() {
        for ((native, positive) in listOf(false to false, false to true, true to false, true to true)) {
            val a = Lit.make(0, positive)
            val b = Lit.make(1, positive)
            val c = Lit.make(2, positive)
            val d = Lit.make(3, positive)
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(Lit.negate(a), b),
                intArrayOf(Lit.negate(a), c), intArrayOf(Lit.negate(b), Lit.negate(c), d)).bake(),
                nativeSat = native)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)
            session.pinBool(3, !positive)

            assertEquals(!positive, session.boolValue(0))
            assertEquals(setOf(Lit.negate(a), d), assertNotNull(session.boolReasonClause(0)).toSet())
        }
    }

    @Test
    fun `disabled implication capture should retain consistent probes without new clauses`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(1, 4),
                intArrayOf(3, 5, 6)).bake(), nativeSat = native)
            val stats = SearchStatsSink()

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1, rootProbeImplications = false), stats)

            assertEquals(1L, stats.inprocessProbes)
            assertEquals(0, session.learnedClauseCount)
            assertEquals(0, session.decisionLevel)
            for (variable in 0..3) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `binary admission should restrict only requested Native probes without propagation opportunities`() {
        for ((native, gate) in listOf(true to true, true to false, false to true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val stats = SearchStatsSink()

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1,
                rootProbeImplications = false, rootProbeBinaryGate = gate), stats)

            assertEquals(if (native && gate) 0L else 1L, stats.inprocessProbes)
            assertEquals(0, session.decisionLevel)
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `an exhausted binary scan should admit a newly learned failed literal without waiting for the interval`() {
        val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2, 4), intArrayOf(1, 2, 5)).bake(),
            nativeSat = true)
        val pass = RootProbingPass()
        val params = BacktrackParams(rootProbeBatch = 1, rootProbeImplications = false,
            rootProbeBinaryGate = true, inprocessingProbeInterval = 10000L)
        val stats = SearchStatsSink()
        assertTrue(pass.optionalWorkDue(0L, params))
        pass.run(session, params, stats)
        val work = session.work
        pass.run(session, params, stats)
        assertEquals(work, session.work)
        assertEquals(0L, stats.inprocessProbes)

        session.addLearnedClause(Clause(intArrayOf(1, 3)), 2)
        assertTrue(pass.optionalWorkDue(0L, params))
        pass.run(session, params, stats)

        assertEquals(1L, stats.inprocessProbes)
        assertEquals(false, session.boolValue(0))
        assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
        assertFalse(pass.optionalWorkDue(9999L, params))
        assertTrue(pass.optionalWorkDue(10000L, params))
    }

    @Test
    fun `binary admission should preserve probing with source binaries or root facts`() {
        for (clauses in listOf(
            arrayOf(intArrayOf(0, 2)),
            arrayOf(intArrayOf(1), intArrayOf(0, 2, 4)),
        )) {
            val session = PropagationSession(rootInprocessingProblem(*clauses).bake(), nativeSat = true)
            val stats = SearchStatsSink()

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1,
                rootProbeImplications = false, rootProbeBinaryGate = true), stats)

            assertEquals(1L, stats.inprocessProbes)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `buffer release should preserve discovered binary eligibility until scope reset`() {
        val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2, 4)).bake(), nativeSat = true)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
        val pass = RootProbingPass()
        val params = BacktrackParams(rootProbeBatch = 1, rootProbeImplications = false, rootProbeBinaryGate = true)
        val stats = SearchStatsSink()
        pass.run(session, params, stats)
        assertEquals(1L, stats.inprocessProbes)
        session.forgetLearnedClauses { _, _ -> false }
        assertEquals(0, session.learnedClauseCount)

        pass.releaseRetainedBuffers()
        pass.run(session, params, stats)
        assertEquals(2L, stats.inprocessProbes)
        pass.reset()
        pass.run(session, params, stats)

        assertEquals(2L, stats.inprocessProbes)
    }

    @Test
    fun `disabled implication capture should retain consumed failed literal units`() {
        for ((native, positive) in listOf(false to false, false to true, true to false, true to true)) {
            val literal = Lit.make(0, positive)
            val session = PropagationSession(
                rootInprocessingProblem(intArrayOf(literal, 2), intArrayOf(literal, 3)).bake(), nativeSat = native,
            )

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2, rootProbeImplications = false), null)

            assertEquals(positive, session.boolValue(0))
            assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
            assertIs<PropagationResult.Unsat>(session.pinBool(0, !positive))
        }
    }

    @Test
    fun `disabling implication capture should fund cancellation of a pending candidate`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(3, 4)).bake(), nativeSat = native)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 1, inprocessingProbeWork = 256L)
            pass.run(session, params, null, containingWork = 63L)
            assertTrue(pass.hasContinuation(session))
            val disabled = params.copy(rootProbeImplications = false)

            pass.continueAtRoot(session, disabled, null, containingWork = 0L)
            assertTrue(pass.hasContinuation(session))
            pass.releaseRetainedBuffers()
            pass.continueAtRoot(session, disabled, null, containingWork = 1L)

            assertFalse(pass.hasContinuation(session))
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `a consistent branch should not assert its implied literals at the root`() {
        for (native in listOf(false, true)) {
            val source = rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(1, 4), intArrayOf(3, 5, 6))
            val session = PropagationSession(source.bake(), nativeSat = native)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1), null)

            assertEquals(0, session.decisionLevel)
            for (variable in 0..3) assertNull(session.boolValue(variable))
            assertEquals(1, session.learnedClauseCount)
            assertEquals(4, session.problem.numBoolVars)
            assertEquals(listOf(3, 5, 6), (source.factors[2] as Clause).literals.toList())
        }
    }

    @Test
    fun `direct binary reasons should not be admitted again`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2)).bake(), nativeSat = native)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)

            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `a deferred consistent implication should resume without another probe`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(3, 4)).bake(), nativeSat = native)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 1, inprocessingProbeWork = 256L)
            val stats = SearchStatsSink()
            pass.run(session, params, stats, containingWork = 63L)
            assertTrue(pass.hasContinuation(session))
            assertEquals(0, session.learnedClauseCount)
            val probes = stats.inprocessProbes

            pass.continueAtRoot(session, params, stats, containingWork = 256L)

            assertEquals(probes, stats.inprocessProbes)
            assertEquals(1, session.learnedClauseCount)
            assertFalse(pass.hasContinuation(session))
        }
    }

    @Test
    fun `own implication history should survive learned compaction`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(1, 4),
                intArrayOf(3, 5, 6)).bake(), nativeSat = native)
            val pass = RootProbingPass()
            val stats = SearchStatsSink()
            pass.run(session, BacktrackParams(rootProbeBatch = 2), stats)
            session.forgetLearnedClauses { _, _ -> false }
            assertEquals(0, session.learnedClauseCount)

            pass.run(session, BacktrackParams(rootProbeBatch = 8), stats)

            assertEquals(0, session.learnedClauseCount)
            assertEquals(1L, stats.snapshot().learning.getValue("inprocessProbeBinaries"))
        }
    }

    @Test
    fun `optional buffer release should preserve the implication admission bound`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(3, 4),
                intArrayOf(5, 6), intArrayOf(7, 0)).bake(), nativeSat = native)
            val pass = RootProbingPass()
            val stats = SearchStatsSink()

            repeat(80) {
                pass.run(session, BacktrackParams(rootProbeBatch = 1), stats)
                session.forgetLearnedClauses { _, _ -> false }
                pass.releaseRetainedBuffers()
            }

            assertTrue(stats.snapshot().learning.getValue("inprocessProbeBinaries") in 1L..64L)
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `unfundable implication storage should retain failed literal probing`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2), intArrayOf(0, 3)).bake(), nativeSat = native)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 2, learnedMemoryScratchBytes = 0L)

            pass.run(session, params, null)

            assertEquals(true, session.boolValue(0))
            assertFalse(pass.hasContinuation(session))
        }
    }

    @Test
    fun `interrupted consistent probes should leave no retained implication`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(1, 4),
                intArrayOf(3, 5, 6)).bake(), nativeSat = native)
            val pass = RootProbingPass()

            var cancelled = false
            val token = ExecutionControl {
                if (session.decisionLevel > 0) cancelled = true
                cancelled
            }

            pass.run(session, BacktrackParams(rootProbeBatch = 1, cancellation = token), null)

            assertTrue(cancelled)
            assertEquals(0, session.learnedClauseCount)
            assertEquals(0, session.decisionLevel)
            assertFalse(pass.hasContinuation(session))
            for (variable in 0..3) assertNull(session.boolValue(variable))
        }
    }

    @Test
    fun `reset should cancel an unadmitted consistent implication`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(3, 4)).bake(), nativeSat = native)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 1, inprocessingProbeWork = 256L)
            pass.run(session, params, null, containingWork = 63L)
            assertTrue(pass.hasContinuation(session))

            pass.reset()
            pass.continueAtRoot(session, params, null, containingWork = 256L)

            assertFalse(pass.hasContinuation(session))
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `buffer release should retain an unadmitted consistent implication`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(3, 4)).bake(), nativeSat = native)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 1, inprocessingProbeWork = 256L)
            val stats = SearchStatsSink()
            pass.run(session, params, stats, containingWork = 63L)
            assertTrue(pass.hasContinuation(session))
            val probes = stats.inprocessProbes

            pass.releaseRetainedBuffers()
            pass.continueAtRoot(session, params, stats, containingWork = 256L)

            assertEquals(probes, stats.inprocessProbes)
            assertEquals(1, session.learnedClauseCount)
            assertFalse(pass.hasContinuation(session))
        }
    }

    @Test
    fun `buffer release should preserve progress toward a later failed literal`() {
        for (native in listOf(false, true)) {
            val source = Problem(8, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(14, 2)), Clause(intArrayOf(14, 3)),
            ))
            val session = PropagationSession(source.bake(), nativeSat = native)
            val pass = RootProbingPass()

            repeat(16) {
                pass.run(session, BacktrackParams(rootProbeBatch = 1), null)
                pass.releaseRetainedBuffers()
            }

            assertEquals(true, session.boolValue(7))
        }
    }

    @Test
    fun `completed root probes should preserve saved search phases`() {
        for ((native, conflicting) in listOf(false to false, false to true, true to false, true to true)) {
            val source = if (conflicting) rootInprocessingProblem(intArrayOf(1, 2), intArrayOf(1, 3))
            else rootInprocessingProblem(intArrayOf(1, 2))
            val session = PropagationSession(source.bake(), nativeSat = native)
            val params = BacktrackParams(rootProbeBatch = 1, phaseSaving = true)
            val phase = PhaseSaving(source.numBoolVars, 0, params)
            session.retractingListener = { phase.capture(it, session) }
            session.pinBool(1, false)
            session.popLast()

            RootProbingPass().run(session, params, null)

            assertEquals(0L, phase.applyPhase(VarRef.Bool(1), sequenceOf(0L, 1L)).first())
        }
    }

    @Test
    fun `root probes should preserve saved integer phases`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
        ))
        val session = PropagationSession(source.bake(), nativeSat = false)
        val params = BacktrackParams(rootProbeBatch = 1, phaseSaving = true)
        val phase = PhaseSaving(source.numBoolVars, source.numIntVars, params)
        session.retractingListener = { phase.capture(it, session) }
        session.pinInt(0, 0L)
        session.popLast()

        RootProbingPass().run(session, params, null)

        assertEquals(0L, phase.applyPhase(VarRef.IntVar(0), sequenceOf(0L, 1L)).first())
    }

    @Test
    fun `interrupted root probes should preserve saved search phases`() {
        for (native in listOf(false, true)) {
            val source = rootInprocessingProblem(intArrayOf(1, 2))
            val session = PropagationSession(source.bake(), nativeSat = native)
            val params = BacktrackParams(rootProbeBatch = 1, rootProbeWork = 1L, phaseSaving = true)
            val phase = PhaseSaving(source.numBoolVars, 0, params)
            session.retractingListener = { phase.capture(it, session) }
            session.pinBool(0, false)
            session.popLast()

            RootProbingPass().run(session, params, null)

            assertEquals(0L, phase.applyPhase(VarRef.Bool(0), sequenceOf(0L, 1L)).first())
        }
    }

    @Test
    fun `ordinary retractions after root probing should still save phases`() {
        for (native in listOf(false, true)) {
            val source = rootInprocessingProblem(intArrayOf(1, 2))
            val session = PropagationSession(source.bake(), nativeSat = native)
            val params = BacktrackParams(rootProbeBatch = 1, phaseSaving = true)
            val phase = PhaseSaving(source.numBoolVars, 0, params)
            session.retractingListener = { phase.capture(it, session) }
            RootProbingPass().run(session, params, null)

            session.pinBool(1, true)
            session.popLast()

            assertEquals(1L, phase.applyPhase(VarRef.Bool(1), sequenceOf(0L, 1L)).first())
        }
    }

    @Test
    fun `a short learned neighborhood should reach a failed literal within one funded probe`() {
        for (native in listOf(false, true)) {
            val source = Problem(8, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(14, 2)), Clause(intArrayOf(14, 3)),
            ))
            val session = PropagationSession(source.bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(14, 12)), 2)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1), null)

            assertEquals(true, session.boolValue(7))
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `an existing integer bound should yield a proved root bound`() {
        for (atMost in listOf(false, true)) {
            val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))
            val session = PropagationSession(source.bake())
            if (atMost) session.boundLeLit(0, 0, true) else session.boundGeLit(0, 1, true)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)

            assertEquals(1L, session.intDomain(0).min)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `integer endpoint progress should survive unrelated atom growth between slices`() {
        for (unrelated in listOf(1, 8)) {
            val source = Problem(0, 2 + unrelated,
                Array(2 + unrelated) { if (it < 2) IntDomain(0, 2) else IntDomain(0, 16) }, arrayOf<Factor>(
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                    Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                ))
            val session = PropagationSession(source.bake())
            session.boundGeLit(2, 1, true)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 1)

            pass.run(session, params, null)
            for (variable in 3 until 2 + unrelated) session.boundGeLit(variable, 1, true)
            pass.run(session, params, null)

            assertEquals(1L, session.intDomain(0).min)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `integer endpoints should yield root bounds without existing atoms`() {
        for (lower in listOf(false, true)) {
            val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), if (lower) LinearOp.GE else LinearOp.LE,
                    if (lower) 1 else 3),
                Linear(intArrayOf(-1, 1), intArrayOf(0, 1), if (lower) LinearOp.LE else LinearOp.GE, 0),
            ))
            val session = PropagationSession(source.bake())

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 4), null)

            assertEquals(if (lower) 1L else 0L, session.intDomain(0).min)
            assertEquals(if (lower) 2L else 1L, session.intDomain(0).max)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `consistent endpoint probes should preserve the root domain`() {
        for (domain in listOf(IntDomain(0, 2), IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 1L),
            IntDomain(Long.MAX_VALUE - 1L, Long.MAX_VALUE))) {
            val session = PropagationSession(Problem(0, 1, arrayOf(domain), emptyArray()).bake())

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)

            assertEquals(domain.min, session.intDomain(0).min)
            assertEquals(domain.max, session.intDomain(0).max)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `a locally capped endpoint probe should restore the complete root`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
        ))
        val session = PropagationSession(source.bake())
        val pass = RootProbingPass()

        pass.run(session, BacktrackParams(rootProbeBatch = 4, rootProbeWork = 1), null)

        assertEquals(0L, session.intDomain(0).min)
        assertEquals(2L, session.intDomain(0).max)
        assertEquals(0, session.decisionLevel)
        pass.run(session, BacktrackParams(rootProbeBatch = 4), null)
        assertEquals(1L, session.intDomain(0).min)
    }

    @Test
    fun `a learned bound neighborhood should reach a failed bound within one funded probe`() {
        val source = Problem(4, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
        ))
        val session = PropagationSession(source.bake())
        val first = session.boundLeLit(0, 0, true)
        val second = session.boundLeLit(1, 0, true)
        session.addLearnedClause(Clause(intArrayOf(Lit.negate(first), second)), 2)

        RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1), null)

        assertEquals(1L, session.intDomain(0).min)
        assertEquals(0, session.decisionLevel)
    }

    @Test
    fun `a locally capped bound probe should restore the complete root`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
        ))
        val session = PropagationSession(source.bake())
        session.boundLeLit(0, 0, true)

        RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1, rootProbeWork = 1), null)

        assertEquals(0L, session.intDomain(0).min)
        assertEquals(2L, session.intDomain(0).max)
        assertEquals(0, session.decisionLevel)
        RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1), null)
        assertEquals(1L, session.intDomain(0).min)
    }

    @Test
    fun `reset should release a retained probe neighborhood`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2)).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val pass = RootProbingPass()
            val initial = SharingMemoryObservation(1024L, 128, { false }, {})
            val baseline = initial.snapshot { pass.retainedMemory(initial, false) }
            pass.run(session, BacktrackParams(rootProbeBatch = 1), null)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.reset()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") >=
                assertNotNull(baseline.completeValues).getValue("ownedPrimitiveBytes") + 8L)
            assertEquals(assertNotNull(baseline.completeValues).getValue("ownedPrimitiveBytes"),
                assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
        }
    }

    @Test
    fun `a locally capped probe should restore the complete root for another invocation`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2), intArrayOf(0, 3)).bake(), nativeSat = native)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2, rootProbeWork = 1), null)

            assertEquals(0, session.decisionLevel)
            assertFalse(session.fixpointCancelled)
            assertFalse(session.probeCancelled)
            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)
            assertEquals(true, session.boolValue(0))
        }
    }

    @Test
    fun `cancelled probing should leave no deduction or standing decision`() {
        val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2), intArrayOf(0, 3)).bake())
        var polls = 0
        session.withCancellation(ExecutionControl { ++polls >= 2 }) {
            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)
        }
        assertEquals(0, session.learnedClauseCount)
        assertEquals(0, session.decisionLevel)
        assertFalse(session.fixpointCancelled)
    }

    @Test
    fun `logical retirement should preserve probe progress toward a useful learned suffix`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                rootInprocessingProblem(intArrayOf(6, 0), intArrayOf(6, 1)).bake(), nativeSat = native,
            )
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(2, 4)), 2)
            session.addLearnedClause(Clause(intArrayOf(6, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 2, 4)), 3)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 1)
            repeat(4) { pass.run(session, params, null) }
            assertNull(session.boolValue(3))
            val count = session.learnedClauseCount
            val retired = session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { it != 2 }
            assertEquals(1, retired)
            assertEquals(count, session.learnedClauseCount)

            pass.run(session, params, null)

            assertEquals(true, session.boolValue(3))
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `physical compaction should invalidate probe positions before a useful learned survivor`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                rootInprocessingProblem(intArrayOf(6, 0), intArrayOf(6, 1)).bake(), nativeSat = native,
            )
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(2, 4)), 2)
            session.addLearnedClause(Clause(intArrayOf(6, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 2, 4)), 3)
            val pass = RootProbingPass()
            val params = BacktrackParams(rootProbeBatch = 1)
            repeat(4) { pass.run(session, params, null) }
            assertNull(session.boolValue(3))
            session.forgetLearnedClauses { index, _ -> index != 0 }
            assertEquals(2, session.learnedClauseCount)

            pass.run(session, params, null)

            assertEquals(true, session.boolValue(3))
            assertEquals(0, session.decisionLevel)
        }
    }
}
