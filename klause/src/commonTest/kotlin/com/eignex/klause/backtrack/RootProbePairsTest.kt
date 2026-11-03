package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootProbePairsTest {
    @Test
    fun `a sparse source neighbor should prove a binary consumed by ordinary propagation`() {
        val rows = listOf<Factor>(
            Clause(intArrayOf(0, 180, 182)),
            Cardinality.atLeastOne(intArrayOf(0, 180, 182)),
            Cardinality(intArrayOf(1, 181, 183), 0, 2),
            Cardinality.exactlyOne(intArrayOf(0, 180, 182)),
            PseudoBoolean(longArrayOf(2, 3, 3), intArrayOf(0, 180, 182), PbOp.GE, 2),
            PseudoBoolean(longArrayOf(2, 3, 3), intArrayOf(1, 181, 183), PbOp.LE, 6),
            PseudoBoolean(longArrayOf(-2, 3, 3), intArrayOf(1, 180, 182), PbOp.GE, 1),
            PseudoBoolean(longArrayOf(2, -3, -3), intArrayOf(1, 180, 182), PbOp.LE, -1),
            PseudoBoolean(longArrayOf(-1, -1, -1), intArrayOf(1, 181, 183), PbOp.EQ, -2),
        )
        for (row in rows) for (native in if (row is Clause) listOf(false, true) else listOf(false)) {
            val source = Problem(100, 0, emptyArray(), arrayOf(
                row, Clause(intArrayOf(183, 184)), Clause(intArrayOf(183, 185)),
            )).bake()
            val projection = PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            val session = PropagationSession(projection, nativeSat = native)
            val params = BacktrackParams(rootProbeBatch = 2)
            val stats = SearchStatsSink()
            val budget = InprocessingBudget(session, params, stats, InprocessingKind.Probe, 4096L, 4096L)
            val implications = RootProbeImplications()
            assertTrue(implications.reserve(params, budget))
            val start = session.work
            session.probeRootLiteral(1)
            assertNull(session.boolValue(90))
            val pairs = RootProbePairs()
            pairs.nextLiteral(1L)
            pairs.nextLiteral(1L)

            val child = assertNotNull(pairs.conflict(session, params, 1, budget, start) {})
            implications.captureConflict(1, child, budget)
            session.popLast()
            assertTrue(implications.advance(session, params, budget))
            session.pinBool(0, false)

            assertEquals(181, child)
            assertEquals(true, session.boolValue(90))
            assertEquals(setOf(0, 180), assertNotNull(session.boolReasonClause(90)).toSet())
            assertEquals(1L, stats.snapshot().learning.getValue("inprocessProbePairSourceEligible"))
        }
    }

    @Test
    fun `source and learned selection should share the candidate bound`() {
        for (native in listOf(false, true)) {
            val source = Problem(100, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 180, 182)), Clause(intArrayOf(183, 184)), Clause(intArrayOf(183, 185)),
            )).bake()
            val projection = PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            val session = PropagationSession(projection, nativeSat = native)
            session.probeRootLiteral(1)
            val params = BacktrackParams(rootProbeBatch = 2)
            val stats = SearchStatsSink()
            val budget = InprocessingBudget(session, params, stats, InprocessingKind.Probe, 4096L, 4096L)
            val start = session.work
            var attempts = 0

            val child = RootProbePairs().conflict(session, params, 1, budget, start,
                IntArray(63) { 1 }) { attempts++ }

            assertNull(child)
            assertEquals(0, attempts)
            assertEquals(1, session.decisionLevel)
            assertNull(session.boolValue(90))
            assertEquals(64L, stats.snapshot().learning.getValue("inprocessProbePairCandidates"))
            assertEquals(1L, stats.snapshot().learning.getValue("inprocessProbePairSourceCandidates"))
        }
    }

    @Test
    fun `source selection should stop before exhausting the containing grant`() {
        for (native in listOf(false, true)) {
            val source = Problem(100, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 180, 182)), Clause(intArrayOf(183, 184)), Clause(intArrayOf(183, 185)),
            )).bake()
            val projection = PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            val session = PropagationSession(projection, nativeSat = native)
            session.probeRootLiteral(1)
            val params = BacktrackParams(rootProbeBatch = 2)
            val stats = SearchStatsSink()
            val budget = InprocessingBudget(session, params, stats, InprocessingKind.Probe, 4096L, 12L)
            val start = session.work
            var attempts = 0

            val child = RootProbePairs().conflict(session, params, 1, budget, start) { attempts++ }

            assertNull(child)
            assertEquals(0, attempts)
            assertEquals(1, session.decisionLevel)
            assertNull(session.boolValue(90))
            assertTrue(session.work - start <= 12L)
            assertEquals(2L, stats.snapshot().learning.getValue("inprocessProbePairSourceCandidates"))
        }
    }

    @Test
    fun `a failed child should restore its complete parent without publishing a clause`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1, 3, 4)), Clause(intArrayOf(1, 3, 5)))), nativeSat = native)
            val start = session.work
            session.pinBool(0, true)
            val params = BacktrackParams(rootProbeBatch = 2)
            val budget = InprocessingBudget(session, params, null, InprocessingKind.Probe, 4096L, 4096L)
            var attempts = 0

            val child = RootProbePairs().conflict(session, params, 0, budget, start) { attempts++ }

            assertEquals(2, child)
            assertEquals(1, attempts)
            assertEquals(1, session.decisionLevel)
            assertEquals(true, session.boolValue(0))
            assertNull(session.boolValue(1))
            assertNull(session.boolValue(2))
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `a failed pair should require remaining containing work`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1, 3, 4)), Clause(intArrayOf(1, 3, 5)))), nativeSat = native)
            val start = session.work
            session.pinBool(0, true)
            val params = BacktrackParams(rootProbeBatch = 2)
            val budget = InprocessingBudget(session, params, null, InprocessingKind.Probe, 4096L, 8L)
            val before = session.work
            var attempts = 0

            val child = RootProbePairs().conflict(session, params, 0, budget, start) { attempts++ }

            assertNull(child)
            assertEquals(0, attempts)
            assertTrue(session.work - before <= 8L)
            assertEquals(1, session.decisionLevel)
            assertNull(session.boolValue(1))
        }
    }

    @Test
    fun `a neighborhood scan should stop before an unfunded child`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1, 3, 4)), Clause(intArrayOf(1, 3, 5)))), nativeSat = native)
            session.pinBool(0, true)
            val params = BacktrackParams(rootProbeBatch = 2)
            val budget = InprocessingBudget(session, params, null, InprocessingKind.Probe, 4096L, 10L)
            val start = session.work
            var attempts = 0

            val child = RootProbePairs().conflict(session, params, 0, budget, start,
                intArrayOf(1, 3, 4)) { attempts++ }

            assertNull(child)
            assertEquals(0, attempts)
            assertEquals(1, session.decisionLevel)
            assertNull(session.boolValue(1))
            assertTrue(session.work - start <= 10L)
        }
    }

    @Test
    fun `a failed pair should require remaining parent probe work`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1, 3, 4)), Clause(intArrayOf(1, 3, 5)))), nativeSat = native)
            session.pinBool(0, true)
            val params = BacktrackParams(rootProbeBatch = 2, rootProbeWork = 1L)
            val budget = InprocessingBudget(session, params, null, InprocessingKind.Probe, 4096L, 4096L)
            val start = session.work
            assertTrue(budget.visit())
            val before = session.work
            var attempts = 0

            val child = RootProbePairs().conflict(session, params, 0, budget, start) { attempts++ }

            assertNull(child)
            assertEquals(0, attempts)
            assertEquals(before, session.work)
            assertEquals(1, session.decisionLevel)
            assertNull(session.boolValue(1))
        }
    }

    @Test
    fun `an interrupted child should restore the complete parent fixpoint`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1, 3, 4)), Clause(intArrayOf(1, 3, 5)))), nativeSat = native)
            val start = session.work
            session.pinBool(0, true)
            var cancelled = false
            val token = Cancellation {
                if (session.boolValue(1) == true) cancelled = true
                cancelled
            }
            val params = BacktrackParams(rootProbeBatch = 2, cancellation = token)
            val budget = InprocessingBudget(session, params, null, InprocessingKind.Probe, 4096L, 4096L)

            val child = RootProbePairs().conflict(session, params, 0, budget, start) {}

            assertTrue(cancelled)
            assertNull(child)
            assertEquals(1, session.decisionLevel)
            assertEquals(true, session.boolValue(0))
            assertNull(session.boolValue(1))
            assertNull(session.boolValue(2))
        }
    }

    @Test
    fun `a completed failed pair should survive deferred admission and buffer release`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1, 3, 4)), Clause(intArrayOf(1, 3, 5)))), nativeSat = native)
            val params = BacktrackParams(rootProbeBatch = 2)
            val stats = SearchStatsSink()
            val budget = InprocessingBudget(session, params, stats, InprocessingKind.Probe, 4096L, 4096L)
            val implications = RootProbeImplications()
            assertTrue(implications.reserve(params, budget))
            val start = session.work
            session.probeRootLiteral(0)
            val child = assertNotNull(RootProbePairs().conflict(session, params, 0, budget, start) {})
            implications.captureConflict(0, child, budget)
            session.popLast()
            val blocked = InprocessingBudget(session, params, stats, InprocessingKind.Probe, 4096L, 0L)
            assertFalse(implications.advance(session, params, blocked))

            implications.releaseRetainedBuffers()
            val funded = InprocessingBudget(session, params, stats, InprocessingKind.Probe, 4096L, 4096L)
            assertTrue(implications.advance(session, params, funded))

            assertFalse(implications.hasContinuation)
            assertEquals(setOf(1, 3), session.learnedClauseLiterals(0).toSet())
            assertEquals(1L, stats.snapshot().learning.getValue("inprocessProbePairBinaries"))
            session.pinBool(0, true)
            assertEquals(false, session.boolValue(1))
        }
    }
}
