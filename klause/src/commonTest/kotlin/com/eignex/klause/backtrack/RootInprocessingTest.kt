package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
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
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootInprocessingTest {
    @Test
    fun `source simplification should install a permanent binary with stable coordinates`() {
        for (native in listOf(false, true)) {
            val problem = rootInprocessingProblem(intArrayOf(1), intArrayOf(0, 2, 4))
            val session = PropagationSession(problem.bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)

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
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1), intArrayOf(0, 2, 4)).bake(), nativeSat = native)
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
            assertTrue(session.work - work < 64L, "native=$native work=${session.work - work}")
        }
    }

    @Test
    fun `learned compaction should retain an indexed source candidate`() {
        for (native in listOf(false, true)) {
            val source = rootInprocessingProblem(
                intArrayOf(1), intArrayOf(4, 6), intArrayOf(4, 6), intArrayOf(0, 2, 4),
            ).bake()
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
    fun `learned compaction should preserve a completed source scan at an unchanged root`() {
        for ((native, prepared) in listOf(false to false, true to false, false to true, true to true)) {
            val source = rootInprocessingProblem(intArrayOf(1), intArrayOf(2, 4), intArrayOf(4, 6), intArrayOf(2, 6)).bake()
            val projection = if (prepared) {
                PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            } else PropagationProblem(source)
            val session = PropagationSession(projection, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(2, 4, 6)), 3)
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(), null)
            session.forgetLearnedClauses { _, _ -> false }
            assertEquals(0, session.learnedClauseCount)
            val work = session.work

            pass.run(session, BacktrackParams(), null)

            assertEquals(work, session.work)
        }
    }

    @Test
    fun `a changed root should refresh source binaries after learned compaction`() {
        for ((native, prepared) in listOf(false to false, true to false, false to true, true to true)) {
            val source = rootInprocessingProblem(intArrayOf(1), intArrayOf(2, 4, 6)).bake()
            val projection = if (prepared) {
                PropagationProblem.preparation(source).asSequence().filterNotNull().last()
            } else PropagationProblem(source)
            val session = PropagationSession(projection, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(2, 6)), 2)
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(), null)
            session.forgetLearnedClauses { _, _ -> false }
            assertEquals(0, session.learnedClauseCount)
            session.addRootDerivedClause(Clause(intArrayOf(3)), 1, 0, false)

            pass.run(session, BacktrackParams(), null)
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(4) { partners.add(it) }

            assertEquals(listOf(6), partners)
        }
    }

    @Test
    fun `physical compaction should invalidate a pending learned candidate`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1)).bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(1, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 4, 6)), 3)
            val pass = RootSimplificationPass()
            val stats = SearchStatsSink()
            val loop = Inprocessing(listOf(pass), cadence = 1, stats = stats)
            repeat(64) {
                if ((stats.snapshot().learning["inprocessRootCandidates"] ?: 0L) < 2L) {
                    loop.onRestart(session, BacktrackParams(inprocessingRootWork = 1L))
                }
            }
            assertEquals(2L, stats.snapshot().learning.getValue("inprocessRootCandidates"))
            assertEquals(2, session.learnedClauseCount)
            session.forgetLearnedClauses { index, _ -> index == 1 }
            assertEquals(1, session.learnedClauseCount)

            repeat(8) { loop.onRestart(session, BacktrackParams()) }
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(4) { partners.add(it) }

            assertEquals(listOf(6), partners)
            assertEquals(1, session.activeLearnedClauseCount)
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
            val source = rootInprocessingProblem(intArrayOf(1), intArrayOf(4, 6), intArrayOf(0, 2, 4)).bake()
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
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val work = session.work

            RootSimplificationPass().run(session, BacktrackParams(), null)

            assertEquals(work, session.work)
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `an unchanged complete root scan should not spend another scan allowance`() {
        for ((native, prepared) in listOf(false to false, true to false, false to true, true to true)) {
            val source = rootInprocessingProblem(intArrayOf(0, 2, 4), intArrayOf(6)).bake()
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
                rootInprocessingProblem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
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
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2, 4)).bake(), nativeSat = native)
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
                rootInprocessingProblem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
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
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1)).bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.importClause(SharedClause(intArrayOf(0, 2, 4), longArrayOf(), 3), origin = 7)

            val loop = Inprocessing(listOf(RootSimplificationPass()), cadence = 1)
            repeat(8) { loop.onRestart(session, BacktrackParams()) }
            session.forgetLearnedClauses { _, _ -> true }
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
            false to 256L, true to 256L)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1)).bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 4, 6)), 3)
            val pass = RootSimplificationPass()
            val params = BacktrackParams(rootSimplifyBatch = 2, inprocessingRootWork = work)
            val stats = SearchStatsSink()
            val loop = Inprocessing(listOf(pass), cadence = 1, stats = stats)

            repeat(100) { loop.onRestart(session, params) }

            assertTrue(session.isLearnedClause(0))
            assertFalse(session.isLearnedClause(1))
            assertTrue((0 until session.learnedClauseCount).any {
                session.isLearnedClause(it) && session.learnedClauseLiterals(it).toSet() == setOf(4, 6)
            }, "native=$native work=$work learning=${stats.snapshot().learning}")
        }
    }

    @Test
    fun `an unfundable learned selection should yield without replacement admission`() {
        for (native in listOf(false, true)) {
            for (grant in listOf(1L, 10L)) {
                val session = PropagationSession(rootInprocessingProblem(intArrayOf(1)).bake(), nativeSat = native)
                assertEquals(native, session.usesNativeSat)
                session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
                session.addLearnedClause(Clause(intArrayOf(0, 4, 6)), 3)
                val loop = Inprocessing(listOf(RootSimplificationPass()), cadence = 1)
                val params = BacktrackParams(inprocessingRootWork = grant)

                repeat(64) { loop.onRestart(session, params) }
                val partners = ArrayList<Int>()
                session.explanationState.forEachBinaryPartner(4) { partners.add(it) }

                assertTrue(partners.isEmpty())
                assertEquals(2, session.learnedClauseCount)
                assertFalse(session.hasLearnedRetirementContinuation)
                assertEquals(0, session.decisionLevel)
            }
        }
    }

    @Test
    fun `a satisfied root clause should retain its live historical reason`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(1)).bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val reason = assertNotNull(session.boolReasonClause(1)).toList()

            val loop = Inprocessing(listOf(RootSimplificationPass()), cadence = 1)
            repeat(4) { loop.onRestart(session, BacktrackParams()) }
            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
            assertEquals(1, session.learnedClauseCount)
        }
    }

    @Test
    fun `a duplicate probe tail should retain another consequence through resumed admission`() {
        for ((native, interrupt) in listOf(false to false, false to true, true to false, true to true)) {
            val session = PropagationSession(Problem(5, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)),
                Clause(intArrayOf(3, 5, 6)), Clause(intArrayOf(3, 5, 8)),
            )).bake(), nativeSat = native)
            val implications = RootProbeImplications()
            val params = BacktrackParams()
            val stats = SearchStatsSink()
            var other = -1

            repeat(2) { attempt ->
                val cancellation = ExecutionControl {
                    attempt == 1 && interrupt &&
                        (stats.snapshot().learning["inprocessProbeImplicationFallbacks"] ?: 0L) > 0L
                }
                val current = params.copy(cancellation = cancellation)
                val budget = InprocessingBudget(session, current, stats, InprocessingKind.Probe, 4096L)
                assertTrue(implications.reserve(current, budget))
                val pins = session.explanationState.boolPinOrder.size
                assertIs<PropagationResult.Implied>(session.probeRootLiteral(0))
                implications.capture(session, 0, pins, budget)
                session.popLast()
                val completed = implications.advance(session, current, budget)
                if (!completed) {
                    assertTrue(implications.hasContinuation)
                    implications.releaseRetainedBuffers()
                    assertTrue(implications.advance(session, params,
                        InprocessingBudget(session, params, stats, InprocessingKind.Probe, 4096L)))
                }
                if (attempt == 0) {
                    val derived = session.learnedClauseLiterals(0).single { Lit.variable(it) != 0 }
                    other = if (Lit.variable(derived) == 3) 4 else 3
                    session.forgetLearnedClauses { _, _ -> false }
                    assertEquals(0, session.learnedClauseCount)
                }
            }
            session.pinBool(other, false)

            assertEquals(false, session.boolValue(0))
            assertEquals(setOf(1, Lit.make(other, true)), assertNotNull(session.boolReasonClause(0)).toSet())
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
    fun `a root slice should refresh binaries using facts proved by its funded probe`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2), intArrayOf(0, 3),
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
    fun `assumption and scoped roots should not produce unconditional units`() {
        for (native in listOf(false, true)) {
            val problem = rootInprocessingProblem(intArrayOf(1, 2, 4), intArrayOf(1, 2, 5))
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
    fun `pass buffer resets should preserve unfinished source retirement`() {
        for ((native, release) in listOf(false to false, false to true, true to false, true to true)) {
            val session = PropagationSession(rootInprocessingProblem(intArrayOf(0, 2, 4), intArrayOf(1)).bake(), nativeSat = native)
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
                rootInprocessingProblem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
            )
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(inprocessingRootWork = 1L), null)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.reset()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") >= 36L)
            assertEquals(12L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `an empty general learned generation should release an obsolete root candidate`() {
        val session = PropagationSession(rootInprocessingProblem(intArrayOf(6)))
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
        assertEquals(12L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
    }
}

internal fun rootInprocessingProblem(vararg clauses: IntArray): Problem = Problem(4, 0, emptyArray(),
    Array<Factor>(clauses.size) { Clause(clauses[it]) })
