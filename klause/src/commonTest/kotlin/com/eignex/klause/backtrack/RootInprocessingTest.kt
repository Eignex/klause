package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.forEachBinaryPartner
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootInprocessingTest {
    private fun problem(vararg clauses: IntArray): Problem = Problem(4, 0, emptyArray(),
        Array<Factor>(clauses.size) { Clause(clauses[it]) })

    @Test
    fun `source simplification should install a permanent binary with stable coordinates`() {
        for (native in listOf(false, true)) {
            val problem = problem(intArrayOf(1), intArrayOf(0, 2, 4))
            val session = PropagationSession(problem.bake(), nativeSat = native)

            RootSimplificationPass().run(session, BacktrackParams(), null)
            session.forgetLearnedClauses { _, _ -> false }
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(2) { partners.add(it) }
            session.pinBool(1, false)

            assertEquals(listOf(4), partners)
            assertEquals(true, session.boolValue(2))
            assertEquals(setOf(2, 4), assertNotNull(session.boolReasonClause(2)).toSet())
            assertEquals(4, session.problem.numBoolVars)
            assertTrue(session.learnedClausePermanent(0))
            assertEquals(listOf(0, 2, 4), (problem.factors[1] as Clause).literals.toList())
        }
    }

    @Test
    fun `a root slice should advance within its configured clause limit`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(1), intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val pass = RootSimplificationPass()
            val params = BacktrackParams(rootSimplifyBatch = 1)

            pass.run(session, params, null)
            assertEquals(0, session.learnedClauseCount)
            pass.run(session, params, null)

            assertEquals(1, session.learnedClauseCount)
            assertEquals(setOf(2, 4), session.learnedClauseLiterals(0).toSet())
        }
    }

    @Test
    fun `a fresh root fact should refresh a late source binary within a small grant`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), Array<Factor>(130) { index ->
                Clause(when (index) {
                    0 -> intArrayOf(1)
                    129 -> intArrayOf(0, 2, 4)
                    else -> intArrayOf(4, 6)
                })
            }).bake()
            val projection = PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            val session = PropagationSession(projection, nativeSat = native)

            RootSimplificationPass().run(session, BacktrackParams(inprocessingRootWork = 64L), null)
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(2) { partners.add(it) }

            assertEquals(listOf(4), partners)
        }
    }

    @Test
    fun `a complete source neighborhood should skip unaffected source clauses`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), Array<Factor>(130) { index ->
                Clause(when (index) {
                    0 -> intArrayOf(1)
                    129 -> intArrayOf(0, 2, 4)
                    else -> intArrayOf(4, 6)
                })
            }).bake()
            val projection = PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            val session = PropagationSession(projection, nativeSat = native)
            val work = session.work
            val stats = SearchStatsSink()

            RootSimplificationPass().run(session, BacktrackParams(), stats)
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(2) { partners.add(it) }

            assertEquals(listOf(4), partners)
            assertEquals(2L, stats.snapshot().learning.getValue("inprocessRootCandidates"))
            assertTrue(session.work - work < 64L)
        }
    }

    @Test
    fun `learned compaction should retain an indexed source candidate`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), Array<Factor>(130) { index ->
                Clause(when (index) {
                    0 -> intArrayOf(1)
                    129 -> intArrayOf(0, 2, 4)
                    else -> intArrayOf(4, 6)
                })
            }).bake()
            val projection = PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            val session = PropagationSession(projection, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(2, 6)), 2)
            val pass = RootSimplificationPass()
            val stats = SearchStatsSink()
            repeat(32) {
                if (stats.snapshot().learning["inprocessRootNeighborhoodCandidates"] == null) {
                    pass.run(session, BacktrackParams(inprocessingRootWork = 1L), stats)
                }
            }
            assertEquals(1L, stats.snapshot().learning.getValue("inprocessRootNeighborhoodCandidates"))
            session.forgetLearnedClauses { _, _ -> false }
            assertEquals(0, session.learnedClauseCount)

            pass.run(session, BacktrackParams(), null)
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(2) { partners.add(it) }

            assertEquals(listOf(4), partners)
        }
    }

    @Test
    fun `new root facts should extend the source neighborhood after an earlier slice`() {
        for ((native, prepared) in listOf(false to false, false to true, true to true)) {
            val source = Problem(6, 0, emptyArray(), Array<Factor>(130) { index ->
                Clause(when (index) {
                    0 -> intArrayOf(10)
                    129 -> intArrayOf(4, 0, 2)
                    else -> intArrayOf(6, 8)
                })
            }).bake()
            val projection = if (prepared) {
                PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            } else PropagationProblem(source)
            val session = PropagationSession(projection, nativeSat = native)
            val pass = RootSimplificationPass()
            val params = BacktrackParams(rootSimplifyBatch = 4, inprocessingRootWork = 64L)
            pass.run(session, params, null)
            session.addRootDerivedClause(Clause(intArrayOf(5)), 1, 0, false)

            pass.run(session, params, null)
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(0) { partners.add(it) }

            assertEquals(listOf(2), partners)
        }
    }

    @Test
    fun `source priority should retain learned clause maintenance within one slice`() {
        for (native in listOf(false, true)) {
            val source = problem(intArrayOf(1), intArrayOf(4, 6), intArrayOf(0, 2, 4)).bake()
            val projection = PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            val session = PropagationSession(projection, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2, 6)), 3)

            RootSimplificationPass().run(session, BacktrackParams(), null)
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(2) { partners.add(it) }

            assertEquals(setOf(4, 6), partners.toSet())
        }
    }

    @Test
    fun `a root without Boolean facts should not spend the simplification allowance`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val work = session.work

            RootSimplificationPass().run(session, BacktrackParams(), null)

            assertEquals(work, session.work)
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `an unchanged complete root scan should not spend another scan allowance`() {
        for ((native, prepared) in listOf(false to false, true to false, false to true, true to true)) {
            val source = problem(intArrayOf(0, 2, 4), intArrayOf(6)).bake()
            val projection = if (prepared) {
                PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            } else PropagationProblem(source)
            val session = PropagationSession(projection, nativeSat = native)
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(), null)
            val work = session.work

            repeat(3) { pass.run(session, BacktrackParams(), null) }

            assertEquals(work, session.work)
        }
    }

    @Test
    fun `new learned clauses should be scanned without replaying unchanged source clauses`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                problem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
            )
            val pass = RootSimplificationPass()
            val stats = SearchStatsSink()
            pass.run(session, BacktrackParams(), stats)
            session.addLearnedClause(Clause(intArrayOf(0, 3, 4)), 3)

            pass.run(session, BacktrackParams(), stats)

            assertEquals(3L, stats.snapshot().learning.getValue("inprocessRootCandidates"))
        }
    }

    @Test
    fun `new unconditional root facts should revisit earlier source clauses`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(), null)
            session.addRootDerivedClause(Clause(intArrayOf(1)), 1, 0, false)

            pass.run(session, BacktrackParams(), null)
            session.pinBool(1, false)

            assertEquals(true, session.boolValue(2))
            assertEquals(setOf(2, 4), assertNotNull(session.boolReasonClause(2)).toSet())
        }
    }

    @Test
    fun `a completed tiny root scan should wait for changed input`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                problem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
            )
            val pass = RootSimplificationPass()
            val params = BacktrackParams(inprocessingRootWork = 1L)
            repeat(12) { pass.run(session, params, null) }
            val work = session.work

            repeat(12) { pass.run(session, params, null) }

            assertEquals(work, session.work)
        }
    }

    @Test
    fun `a clause beyond the remaining literal space should wait for the next slice`() {
        for (native in listOf(false, true)) {
            val source = Problem(5001, 0, emptyArray(), arrayOf<Factor>(
                Clause(IntArray(5000) { it * 2 }), Clause(IntArray(5000) { it * 2 + 1 }),
                Clause(intArrayOf(0)),
            ))
            val session = PropagationSession(source.bake(), nativeSat = native)
            val pass = RootSimplificationPass()
            val stats = SearchStatsSink()

            pass.run(session, BacktrackParams(), stats)
            assertEquals(1L, stats.snapshot().learning.getValue("inprocessRootEligible"))

            pass.run(session, BacktrackParams(), stats)

            assertEquals(2L, stats.snapshot().learning.getValue("inprocessRootEligible"))
        }
    }

    @Test
    fun `root refresh should preserve imported origin through compaction`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(1)).bake(), nativeSat = native)
            session.importClause(SharedClause(intArrayOf(0, 2, 4), longArrayOf(), 3), origin = 7)

            RootSimplificationPass().run(session, BacktrackParams(), null)
            session.pinBool(1, false)
            var credited = 0L
            session.drainImportUses { origin, uses -> if (origin == 7) credited += uses }

            assertEquals(true, session.boolValue(2))
            assertEquals(1, session.learnedClauseCount)
            assertEquals(7, session.learnedClauseOrigin(0))
            assertTrue(credited > 0L)
        }
    }

    @Test
    fun `a retained active reason should not starve later root candidates`() {
        for ((native, work) in listOf(false to Long.MAX_VALUE, true to Long.MAX_VALUE,
            false to 10L, true to 10L, false to 1L, true to 1L)) {
            val session = PropagationSession(problem(intArrayOf(1)).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 4, 6)), 3)
            val pass = RootSimplificationPass()
            val params = BacktrackParams(rootSimplifyBatch = 2, inprocessingRootWork = work)
            val stats = SearchStatsSink()

            repeat(100) { pass.run(session, params, stats) }

            assertTrue((0 until session.learnedClauseCount).any {
                session.learnedClauseLiterals(it).toSet() == setOf(4, 6)
            }, "native=$native work=$work learning=${stats.snapshot().learning}")
        }
    }

    @Test
    fun `a satisfied root clause should retain its live historical reason`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(1)).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val reason = assertNotNull(session.boolReasonClause(1)).toList()

            RootSimplificationPass().run(session, BacktrackParams(), null)
            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
            assertEquals(1, session.learnedClauseCount)
        }
    }

    @Test
    fun `failed literal probing should assert a consumed unconditional unit`() {
        for ((native, positive) in listOf(false to false, false to true, true to false, true to true)) {
            val literal = Lit.make(0, positive)
            val session = PropagationSession(
                problem(intArrayOf(literal, 2), intArrayOf(literal, 3)).bake(), nativeSat = native,
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
    fun `completed root probes should preserve saved search phases`() {
        for ((native, conflicting) in listOf(false to false, false to true, true to false, true to true)) {
            val source = if (conflicting) problem(intArrayOf(1, 2), intArrayOf(1, 3))
                else problem(intArrayOf(1, 2))
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
            val source = problem(intArrayOf(1, 2))
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
            val source = problem(intArrayOf(1, 2))
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
    fun `a shortened source neighborhood should reach a failed literal within one funded probe`() {
        for (native in listOf(false, true)) {
            val source = Problem(9, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(17)), Clause(intArrayOf(14, 2)), Clause(intArrayOf(14, 3)),
                Clause(intArrayOf(14, 12, 16)),
            ))
            val session = PropagationSession(source.bake(), nativeSat = native)
            RootSimplificationPass().run(session, BacktrackParams(), null)

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
            val session = PropagationSession(problem(intArrayOf(0, 2)).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val pass = RootProbingPass()
            pass.run(session, BacktrackParams(rootProbeBatch = 1), null)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.reset()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") >= 8L)
            assertEquals(0L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
        }
    }

    @Test
    fun `a root slice should refresh binaries using facts proved by its funded probe`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2), intArrayOf(0, 3),
                intArrayOf(1, 4, 6)).bake(), nativeSat = native)
            val params = BacktrackParams(rootSimplification = true, rootProbeBatch = 2)
            val loop = assertNotNull(Inprocessing.from(params))

            loop.onRestart(session, params)
            session.pinBool(2, false)

            assertEquals(true, session.boolValue(3))
            assertEquals(setOf(4, 6), assertNotNull(session.boolReasonClause(3)).toSet())
        }
    }

    @Test
    fun `a locally capped probe should restore the complete root for another invocation`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2), intArrayOf(0, 3)).bake(), nativeSat = native)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2, rootProbeWork = 1), null)

            assertEquals(0, session.decisionLevel)
            assertFalse(session.fixpointCancelled)
            assertFalse(session.probeCancelled)
            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)
            assertEquals(true, session.boolValue(0))
        }
    }

    @Test
    fun `assumption and scoped roots should not produce unconditional units`() {
        for (native in listOf(false, true)) {
            val problem = problem(intArrayOf(1, 2, 4), intArrayOf(1, 2, 5))
            val seeded = PropagationSession(problem.bake(), nativeSat = native)
            seeded.seed(Assumptions(bools = mapOf(0 to true)))
            val scoped = PropagationSession(problem.bake(), nativeSat = native)
            scoped.addLearnedClause(Clause(intArrayOf(0)), 1, permanent = true)
            for (session in listOf(seeded, scoped)) {
                val count = session.learnedClauseCount
                RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 8), null)
                RootSimplificationPass().run(session, BacktrackParams(), null)
                assertEquals(count, session.learnedClauseCount)
                assertNull(session.boolValue(1))
            }
            assertNull(Inprocessing.from(BacktrackParams(rootSimplification = true, rootProbeBatch = 8,
                assumptions = Assumptions(bools = mapOf(0 to true)))))
        }
    }

    @Test
    fun `cancelled probing should leave no deduction or standing decision`() {
        val session = PropagationSession(problem(intArrayOf(0, 2), intArrayOf(0, 3)).bake())
        var polls = 0
        session.withCancellation(Cancellation { ++polls >= 2 }) {
            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)
        }
        assertEquals(0, session.learnedClauseCount)
        assertEquals(0, session.decisionLevel)
        assertFalse(session.fixpointCancelled)
    }

    @Test
    fun `pass buffer resets should preserve unfinished source retirement`() {
        for ((native, release) in listOf(false to false, false to true, true to false, true to true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2, 4), intArrayOf(1)).bake(), nativeSat = native)
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(inprocessingRootWork = 11L), null)
            assertEquals(1, session.learnedClauseCount)

            if (release) pass.releaseRetainedBuffers() else pass.reset()
            pass.run(session, BacktrackParams(), null)
            session.pinBool(1, false)

            assertEquals(1, session.learnedClauseCount)
            assertEquals(true, session.boolValue(2))
        }
    }

    @Test
    fun `reset should release a root candidate retained across a tiny slice`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                problem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
            )
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(inprocessingRootWork = 1L), null)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.reset()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") >= 24L)
            assertEquals(0L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `an empty general learned generation should release an obsolete root candidate`() {
        val session = PropagationSession(problem(intArrayOf(6)))
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
        val pass = RootSimplificationPass()
        repeat(8) { pass.run(session, BacktrackParams(inprocessingRootWork = 1L), null) }
        val before = SharingMemoryObservation(1024L, 128, { false }, {})
        val retained = before.snapshot { pass.retainedMemory(before, false) }
        assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") > 0L)

        session.forgetLearnedClauses { _, _ -> false }
        pass.run(session, BacktrackParams(), null)
        val after = SharingMemoryObservation(1024L, 128, { false }, {})
        val released = after.snapshot { pass.retainedMemory(after, false) }

        assertEquals(0, session.learnedClauseCount)
        assertFalse(session.fixpointCancelled)
        assertEquals(0L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
    }
}
