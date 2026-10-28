package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.*
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropagationSessionTest {
    @Test
    fun `popping bound decisions should preserve an earlier exact integer pin`() {
        for (lower in listOf(false, true)) {
            val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray()))
            session.pinInt(0, 2L)
            if (lower) session.pinIntAtLeast(0, 1L) else session.pinIntAtMost(0, 3L)

            session.popLast()

            assertEquals(Assumptions(ints = mapOf(0 to 2L)), session.currentAssumptions())
            if (lower) session.pinIntAtLeast(0, 1L) else session.pinIntAtMost(0, 3L)
            session.popUntilUnpinned(VarKind.Int, 0)
            assertEquals(IntDomain(0, 3), session.intDomain(0))
            assertTrue(session.currentAssumptions().isEmpty)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `reseeding should discard bound decisions above an exact integer pin`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray()))
        val assumptions = Assumptions(ints = mapOf(0 to 2L))
        session.seed(assumptions)
        session.pinIntAtLeast(0, 1L)

        session.reseedFrom(assumptions)

        assertEquals(assumptions, session.currentAssumptions())
        assertEquals(1, session.decisionLevel)
    }

    @Test
    fun `survivor restriction exclusions should retain their seed level until reseeding`() {
        for (maximum in listOf(3L, Long.MAX_VALUE)) {
            val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, maximum)), emptyArray()))
            val assumptions = Assumptions(
                intArrayOf(), booleanArrayOf(), intArrayOf(), longArrayOf(),
                DeducedRestrictions(intSetKeys = intArrayOf(0), intSetOffsets = intArrayOf(0, 2),
                    intSetValues = longArrayOf(1, maximum)),
            )
            assertIs<PropagationResult.Implied>(session.seed(assumptions))

            val equality = session.equalityLit(0, 2L)
            val state = session.explanationState

            assertEquals(false, session.litTruth(equality))
            assertEquals(1, state.atomLevelForConflict(Lit.variable(equality)))
            assertTrue(state.carvedAt(0, 2L) > 0)
            assertIs<PropagationResult.Implied>(session.reseedFrom(Assumptions.None))
            assertNull(session.litTruth(equality))
            assertFalse(state.holeHistHas(0, 2L))
            assertEquals(-1, state.carvedAt(0, 2L))
        }
    }

    @Test
    fun `bound restriction snapshots should not fabricate exact integer pins`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray()))
        session.seed(Assumptions.None.withTightenedMin(0, 1L))

        val snapshot = session.currentAssumptions()

        assertTrue(snapshot.ints.isEmpty())
    }

    @Test
    fun `domain restriction seeds should apply and disappear on reseeding`() {
        val allowed = Assumptions(
            intArrayOf(), booleanArrayOf(), intArrayOf(), longArrayOf(),
            DeducedRestrictions(intSetKeys = intArrayOf(0), intSetOffsets = intArrayOf(0, 2),
                intSetValues = longArrayOf(1, 3)),
        )
        val cases = listOf(
            Assumptions.None.withTightenedMin(0, 1L) to listOf(1L, 2L, 3L),
            Assumptions.None.withTightenedMax(0, 2L) to listOf(0L, 1L, 2L),
            Assumptions.None.withIntHole(0, 1L) to listOf(0L, 2L, 3L),
            allowed to listOf(1L, 3L),
        )

        for ((assumptions, values) in cases) {
            for (prepared in listOf(false, true)) {
                val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray()))

                val result = if (prepared) session.prepareSeed(assumptions).asSequence().filterNotNull().last()
                else session.seed(assumptions)

                assertIs<PropagationResult.Implied>(result)
                assertEquals(values, (0L..3L).filter { it in session.intDomain(0) })
                assertIs<PropagationResult.Implied>(session.reseedFrom(Assumptions.None))
                assertEquals(IntDomain(0, 3), session.intDomain(0))
                assertEquals(0, session.decisionLevel)
                assertIs<PropagationResult.Implied>(session.reseedFrom(assumptions))
                assertEquals(values, (0L..3L).filter { it in session.intDomain(0) })
            }
        }
    }

    @Test
    fun `cancelled domain restriction reseeding should retry under a fresh invocation`() {
        var cancelled = false
        val session = PropagationSession(
            Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray()),
            Cancellation { cancelled },
        )
        val assumptions = Assumptions.None.withTightenedMin(0, 2L)
        cancelled = true
        assertIs<PropagationResult.Implied>(session.reseedFrom(assumptions))
        assertTrue(session.fixpointCancelled)

        cancelled = false
        val result = session.reseedFrom(assumptions)

        assertIs<PropagationResult.Implied>(result)
        assertFalse(session.fixpointCancelled)
        assertEquals(IntDomain(2, 3), session.intDomain(0))
    }

    @Test
    fun `a factor conflict after external fixing should retain a valid asserting clause`() {
        val session = PropagationSession(Problem(1, 2, Array(2) { IntDomain(0, 10) }, emptyArray()))
        assertIs<PropagationResult.Implied>(session.pinIntAtLeast(0, 1))
        val excluded = session.boundLeLit(1, 5, false)
        for (positive in listOf(false, true)) {
            assertIs<PropagationResult.Implied>(session.addLearnedClause(
                Clause(intArrayOf(excluded, Lit.make(0, positive))), 2,
            ))
        }

        val conflict = assertIs<PropagationResult.Unsat>(session.implyIntAtMost(1, 5))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        assertEquals(listOf(excluded), learned.literals.toList())
        assertTrue(learned.asserting)
        session.popToLevel(learned.backjumpLevel)
        assertIs<PropagationResult.Implied>(session.addLearnedClause(Clause(learned.literals), learned.lbd))
        assertEquals(6L, session.intDomain(1).min)
    }

    @Test
    fun `a conflicting external bound should not analyze an unrelated atom clause`() {
        for (lower in listOf(false, true)) {
            for (padding in listOf(0, 7)) {
                val session = PropagationSession(Problem(0, 3, Array(3) { IntDomain(0, 10) }, emptyArray()))
                val pin = if (lower) session.pinIntAtMost(0, 4) else session.pinIntAtLeast(0, 6)
                assertIs<PropagationResult.Implied>(pin)
                repeat(padding) { session.boundGeLit(1, it.toLong() + 1, true) }
                val left = session.boundGeLit(1, 8, true)
                val right = session.boundLeLit(2, 2, true)
                assertIs<PropagationResult.Implied>(session.addLearnedClause(Clause(intArrayOf(left, right)), 2))

                val result = if (lower) session.implyIntAtLeast(0, 5) else session.implyIntAtMost(0, 5)

                assertNull(assertIs<PropagationResult.Unsat>(result).learnedClause)
                assertNull(session.litTruth(left))
                assertNull(session.litTruth(right))
                assertEquals(if (lower) IntDomain(0, 4) else IntDomain(6, 10), session.intDomain(0))
            }
        }
    }

    @Test
    fun `an external fixing should belong to the active decision level after a root factor fires`() {
        for (lower in listOf(false, true)) {
            val session = PropagationSession(Problem(0, 3, Array(3) { IntDomain(0, 10) }, emptyArray()))
            assertIs<PropagationResult.Implied>(session.pinIntAtLeast(0, 1))
            val root = session.boundLeLit(2, 10, true)
            assertIs<PropagationResult.Implied>(session.addLearnedClause(Clause(intArrayOf(root)), 1))

            val result = if (lower) session.implyIntAtLeast(1, 5) else session.implyIntAtMost(1, 5)

            assertIs<PropagationResult.Implied>(result)
            val negated = if (lower) session.boundGeLit(1, 5, false) else session.boundLeLit(1, 5, false)
            val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(
                session.analyzeConflictClause(intArrayOf(negated)),
            )
            assertEquals(listOf(1), learned.decisionLevels.toList())
            assertTrue(learned.asserting)
            session.popToLevel(0)
            assertNull(session.litTruth(negated))
        }
    }

    @Test
    fun `interrupted reseeding should retract an incomplete equality before retrying`() {
        var armed = false
        lateinit var session: PropagationSession
        val token = Cancellation {
            armed && session.litTruth(session.boundGeLit(0, 16, true)) == true
        }
        session = PropagationSession(Problem(0, 2, Array(2) { IntDomain(0, 255) }, emptyArray()), token)
        repeat(128) { session.boundGeLit(0, it.toLong() + 1, true) }
        val desired = Assumptions.None.withInt(0, 129).withInt(1, 17)
        armed = true

        assertIs<PropagationResult.Implied>(session.reseedFrom(desired))

        assertTrue(session.fixpointCancelled)
        assertEquals(0, session.decisionLevel)
        assertEquals(IntDomain(0, 255), session.intDomain(0))
        assertEquals(IntDomain(0, 255), session.intDomain(1))
        armed = false
        assertIs<PropagationResult.Implied>(session.reseedFrom(desired))
        assertFalse(session.fixpointCancelled)
        assertEquals(IntDomain(129, 129), session.intDomain(0))
        assertEquals(IntDomain(17, 17), session.intDomain(1))
    }

    @Test
    fun `cancelled reseeding should retain only its completed prefix`() {
        for (boolean in listOf(false, true)) {
            var armed = false
            lateinit var session: PropagationSession
            val token = Cancellation {
                armed && if (boolean) session.boolValue(0) == true else session.intDomain(0).isFixed
            }
            session = PropagationSession(Problem(2, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), token)
            val desired = if (boolean) {
                Assumptions.None.withBool(0, true).withBool(1, true)
            } else {
                Assumptions.None.withInt(0, 1).withInt(1, 1)
            }
            armed = true

            assertIs<PropagationResult.Implied>(session.reseedFrom(desired))

            assertTrue(session.fixpointCancelled)
            assertEquals(1, session.decisionLevel)
            if (boolean) assertNull(session.boolValue(1)) else assertEquals(IntDomain(0, 1), session.intDomain(1))
            armed = false
            assertIs<PropagationResult.Implied>(session.reseedFrom(desired))
            assertFalse(session.fixpointCancelled)
            assertEquals(2, session.decisionLevel)
        }
    }

    @Test
    fun `cancelled seeding should leave the remaining assumptions unapplied`() {
        for (boolean in listOf(false, true)) {
            val problem = if (boolean) {
                Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
            } else {
                Problem(
                    0,
                    2,
                    Array(2) { IntDomain(0, 1) },
                    arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
                )
            }
            var polls = 0
            val session = PropagationSession(problem, Cancellation { ++polls >= 3 }, propagationCancelFloor = 0)
            val assumptions = if (boolean) {
                Assumptions.None.withBool(0, true).withBool(1, true)
            } else {
                Assumptions.None.withInt(0, 1L).withInt(1, 1L)
            }

            assertIs<PropagationResult.Implied>(session.seed(assumptions))

            assertTrue(session.fixpointCancelled)
            if (boolean) {
                assertEquals(true, session.boolValue(0))
                assertNull(session.boolValue(1))
            } else {
                assertEquals(IntDomain(1, 1), session.intDomain(0))
                assertEquals(IntDomain(0, 1), session.intDomain(1))
            }
        }
    }

    @Test
    fun `a late mixed clause implication should survive an asserting backjump`() {
        val session = PropagationSession(
            Problem(
                3,
                2,
                Array(2) { IntDomain(0, 2) },
                arrayOf(
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false))),
                ),
            ),
        )
        assertIs<PropagationResult.Implied>(session.pinIntAtLeast(0, 1))
        assertIs<PropagationResult.Implied>(session.pinIntAtLeast(1, 1))
        val bound = session.explanationState.atomVarGe(0, 1)
        assertIs<PropagationResult.Implied>(
            session.addLearnedClause(Clause(intArrayOf(Lit.make(bound, false), Lit.make(0, true))), lbd = 2),
        )

        val conflict = assertIs<PropagationResult.Unsat>(session.pinBool(1, true))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        session.popToLevel(learned.backjumpLevel)
        assertIs<PropagationResult.Implied>(session.addLearnedClause(Clause(learned.literals), learned.lbd))

        assertEquals(false, session.boolValue(1))
    }

    @Test
    fun `integer probes should expose complete domains until popped`() {
        val session = PropagationSession(
            Problem(
                0,
                2,
                arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
                arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
            ),
        )

        assertIs<PropagationResult.Implied>(session.probeInt(1, 3L))

        assertEquals(3L, session.intDomain(0).max)
        assertEquals(3L, session.intDomain(1).min)
        assertEquals(3L, session.intDomain(1).max)
        session.popLast()
        assertEquals(10L, session.intDomain(0).max)
        assertEquals(0L, session.intDomain(1).min)
        assertEquals(10L, session.intDomain(1).max)
    }

    @Test
    fun `Boolean probes should retain native deductions until popped`() {
        val session = PropagationSession(
            Problem(
                2,
                0,
                emptyArray(),
                arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
            ),
        )

        assertIs<PropagationResult.Implied>(session.probeBool(0, true))

        assertEquals(true, session.boolValue(1))
        session.popLast()
        assertNull(session.boolValue(0))
        assertNull(session.boolValue(1))
    }

    @Test
    fun `repeated endpoint tightening should publish the final bound`() {
        for (upper in listOf(false, true)) {
            val coefficients = if (upper) listOf(2, 1) else listOf(1, 2)
            val session = PropagationSession(
                Problem(
                    0,
                    2,
                    arrayOf(IntDomain(0, 20), IntDomain(0, 10)),
                    coefficients.map { coefficient ->
                        Linear(
                            intArrayOf(if (upper) 1 else -1, if (upper) -coefficient else coefficient),
                            intArrayOf(0, 1),
                            LinearOp.LE,
                            0,
                        )
                    },
                ),
            )

            val result = assertIs<PropagationResult.Implied>(
                if (upper) session.pinIntAtMost(1, 3L) else session.pinIntAtLeast(1, 3L),
            )

            assertEquals(
                if (upper) 3L else 6L,
                if (upper) result.intMaxOrNullCompat(0) else result.intMinOrNullCompat(0),
            )
            assertNull(if (upper) result.intMinOrNullCompat(0) else result.intMaxOrNullCompat(0))
        }
    }

    @Test
    fun `endpoint publications should follow a new push after rollback`() {
        val session = PropagationSession(
            Problem(
                0,
                2,
                arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
                arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
            ),
        )
        assertIs<PropagationResult.Implied>(session.pinIntAtMost(1, 3L))
        session.popLast()

        val result = assertIs<PropagationResult.Implied>(session.pinIntAtMost(1, 7L))

        assertEquals(7L, result.intMaxOrNullCompat(0))
        assertNull(result.intMinOrNullCompat(0))
    }

    @Test
    fun `incremental propagation should report changed non-singleton endpoints`() {
        for (upper in listOf(false, true)) {
            val session = PropagationSession(
                Problem(
                    0,
                    3,
                    arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
                    arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
                ),
            )

            val result = assertIs<PropagationResult.Implied>(
                if (upper) session.pinIntAtMost(1, 3L) else session.pinIntAtLeast(0, 5L),
            )

            assertEquals(
                if (upper) 3L else 5L,
                if (upper) result.intMaxOrNullCompat(0) else result.intMinOrNullCompat(1),
            )
            assertNull(if (upper) result.intMinOrNullCompat(0) else result.intMaxOrNullCompat(1))
            assertNull(result.intMinOrNullCompat(2))
            assertNull(result.intMaxOrNullCompat(2))
        }
    }

    @Test
    fun `the root domain should stay at the seed while the live domain follows the trail`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(),
        )
        val s = PropagationSession(p)
        assertIs<PropagationResult.Implied>(s.seed(Assumptions.None))
        assertIs<PropagationResult.Implied>(s.pinIntAtLeast(0, 4L))

        assertEquals(4L, s.intDomain(0).min)
        assertEquals(0L, s.rootIntDomain(0).min)
        assertEquals(9L, s.rootIntDomain(0).max)
    }

    @Test
    fun `an imported clause that forces a unit should count a use for its origin`() {
        for (native in listOf(false, true)) {
            val p = Problem(numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>())
            val s = PropagationSession(p, nativeSat = native)
            s.seed(Assumptions.None)
            s.importClause(SharedClause(intArrayOf(Lit.make(0, false), Lit.make(1, true)), LongArray(0), lbd = 2), 4)
            s.importClause(SharedClause(intArrayOf(Lit.make(2, true), Lit.make(1, false)), LongArray(0), lbd = 2))

            s.pinBool(0, true)
            val uses = HashMap<Int, Long>()
            s.drainImportUses { origin, n -> uses[origin] = n }

            assertEquals(mapOf(4 to 1L), uses, "native lane: $native")
        }
    }

    @Test
    fun `popUntilUnpinned should pop to the target`() {
        val p = Problem(3, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        s.pinBool(1, true)
        s.pinBool(2, true)
        s.popUntilUnpinned(VarKind.Bool, 1)
        assertEquals(Assumptions(bools = mapOf(0 to true)), s.currentAssumptions())
    }

    @Test
    fun `push should report only newly implied facts`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
            ),
        )
        val p = Problem(3, 0, emptyArray(), listOf(factor))
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        val first = s.pinBool(0, true)
        val firstImpl = assertIs<PropagationResult.Implied>(first)
        assertEquals(false, firstImpl.bools[1])
        assertEquals(false, firstImpl.bools[2])
        val second = s.pinBool(0, true)
        val secondImpl = assertIs<PropagationResult.Implied>(second)
        assertTrue(secondImpl.bools.isEmpty())
        assertTrue(secondImpl.ints.isEmpty())
    }

    @Test
    fun `a fixpoint cut short by the deadline should flag fixpointCancelled`() {
        // cancelFloor 0 makes the poll engage from the first fire.
        val p = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))))
        var armed = false
        val s = PropagationSession(p, Cancellation { armed }, propagationCancelFloor = 0)
        s.seed(Assumptions.None)
        assertTrue(!s.fixpointCancelled)
        armed = true
        s.pinBool(0, true)
        assertTrue(s.fixpointCancelled)
    }

    @Test
    fun `undo should preserve spent work while restoring the propagated state`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val session = PropagationSession(problem)
        val root = session.work
        session.pinBool(0, true)
        val pushed = session.work

        session.popLast()

        assertTrue(pushed > root)
        assertTrue(session.work >= pushed)
        assertEquals(null, session.boolValue(1))
        assertEquals(root, session.rootWork)
    }

    @Test
    fun `push then pop should restore the fresh state`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        val firstPush = assertIs<PropagationResult.Implied>(s.pinBool(0, true))
        s.popLast()
        val secondPush = assertIs<PropagationResult.Implied>(s.pinBool(0, true))
        assertEquals(firstPush, secondPush)
    }

    @Test
    fun `popToLevel mid-stack should restore the intermediate fixpoint`() {
        val p = Problem(5, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        s.pinBool(1, true)
        s.pinBool(2, true)
        assertEquals(3, s.decisionLevel)
        s.popToLevel(1)
        assertEquals(1, s.decisionLevel)
        assertEquals(Assumptions(bools = mapOf(0 to true)), s.currentAssumptions())
        s.pinBool(3, false)
        assertEquals(2, s.decisionLevel)
        assertEquals(
            Assumptions(bools = mapOf(0 to true, 3 to false)),
            s.currentAssumptions(),
        )
    }

    @Test
    fun `a conflict should leave the session at the pre-push level`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, false)
        assertEquals(1, s.decisionLevel)
        val u = assertIs<PropagationResult.Unsat>(s.pinBool(1, false))
        assertEquals(setOf(1, 2), u.conflictLevels.toSet())
        assertEquals(1, s.decisionLevel)
        assertEquals(Assumptions(bools = mapOf(0 to false)), s.currentAssumptions())
        assertIs<PropagationResult.Implied>(s.pinBool(1, true))
    }

    @Test
    fun `a seed conflict should return Unsat with seed levels`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        val u = assertIs<PropagationResult.Unsat>(
            s.seed(Assumptions(bools = mapOf(0 to false, 1 to false))),
        )
        assertEquals(setOf(0, 1), u.conflictBools.toSet())
        assertEquals(1, s.decisionLevel)
    }

    @Test
    fun `re-seed should clear the prior trail`() {
        val p = Problem(3, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions(bools = mapOf(0 to true)))
        s.pinBool(1, false)
        assertEquals(2, s.decisionLevel)
        s.seed(Assumptions(bools = mapOf(2 to true)))
        assertEquals(1, s.decisionLevel)
        assertEquals(Assumptions(bools = mapOf(2 to true)), s.currentAssumptions())
    }

    @Test
    fun `reseedFrom should leave the session identical to a fresh seed of the same set`() {
        fun problem() = Problem(
            numBoolVars = 3,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
            ),
        )
        val cases = listOf(
            Assumptions(bools = mapOf(0 to false)) to Assumptions(bools = mapOf(2 to true), ints = mapOf(0 to 3L)),
            Assumptions(bools = mapOf(0 to false, 2 to true)) to
                Assumptions(bools = mapOf(0 to false), ints = mapOf(0 to 3L)),
            Assumptions(bools = mapOf(0 to false), ints = mapOf(0 to 3L)) to
                Assumptions(bools = mapOf(0 to false), ints = mapOf(0 to 3L)),
        )
        for ((from, to) in cases) {
            val incremental = PropagationSession(problem())
            incremental.seed(from)
            val incrementalResult = incremental.reseedFrom(to)

            val fresh = PropagationSession(problem())
            val freshResult = fresh.seed(to)

            assertEquals(freshResult, incrementalResult, "implied facts diverge for $from → $to")
            assertEquals(fresh.currentAssumptions(), incremental.currentAssumptions())
            assertEquals(fresh.decisionLevel, incremental.decisionLevel)
        }
    }

    @Test
    fun `reseedFrom should pop leftover search decisions above the seed`() {
        val p = Problem(5, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions(bools = mapOf(0 to true)))
        s.pinBool(3, true)
        s.pinBool(4, false)
        assertEquals(3, s.decisionLevel)
        s.reseedFrom(Assumptions(bools = mapOf(0 to true, 1 to false)))
        assertEquals(2, s.decisionLevel)
        assertEquals(Assumptions(bools = mapOf(0 to true, 1 to false)), s.currentAssumptions())
    }

    @Test
    fun `reseedFrom onto an infeasible fragment should return Unsat`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions(bools = mapOf(0 to true)))
        assertIs<PropagationResult.Unsat>(s.reseedFrom(Assumptions(bools = mapOf(0 to false, 1 to false))))
    }

    @Test
    fun `conflict levels should identify the responsible decisions`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertIs<PropagationResult.Implied>(s.pinBool(0, false)) // level 1
        assertIs<PropagationResult.Implied>(s.pinBool(2, true)) // level 2
        val u = assertIs<PropagationResult.Unsat>(s.pinBool(1, false)) // level 3 → Unsat
        assertEquals(setOf(1, 3), u.conflictLevels.toSet())
        assertEquals(setOf(0, 1), u.conflictBools.toSet())
        assertTrue(2 !in u.conflictBools)
    }

    @Test
    fun `propagated implications should inherit the deepest contributing level`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertIs<PropagationResult.Implied>(s.pinBool(0, true)) // level 1; forces y=true
        assertIs<PropagationResult.Implied>(s.pinBool(2, true)) // level 2; irrelevant
        val u = assertIs<PropagationResult.Unsat>(s.pinBool(1, false)) // level 3
        assertEquals(setOf(1, 3), u.conflictLevels.toSet())
    }

    @Test
    fun `seed pins should occupy levels 1 through N`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val u = assertIs<PropagationResult.Unsat>(
            p.propagate(Assumptions(bools = mapOf(0 to false, 1 to false))),
        )
        assertEquals(setOf(1, 2), u.conflictLevels.toSet())
        assertEquals(setOf(0, 1), u.conflictBools.toSet())
    }

    @Test
    fun `skipPermanent should exclude permanent clauses but keep ordinary learned clauses`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyList()))
        val ordinary = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val permanent = Clause(intArrayOf(Lit.make(2, true)))
        session.addLearnedClause(ordinary, lbd = 2, permanent = false)
        session.addLearnedClause(permanent, lbd = 1, permanent = true)

        val all = session.exportGlueClauses(maxLbd = 4, maxLen = 8, skipPermanent = false)
        val gated = session.exportGlueClauses(maxLbd = 4, maxLen = 8, skipPermanent = true)
        assertEquals(2, all.size)
        assertEquals(1, gated.size)
    }

    @Test
    fun `a clause another arm originated should be left for that arm to publish`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyList()))
        session.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))), lbd = 2)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))), lbd = 2, origin = 1)

        val exported = session.exportGlueClauses(maxLbd = 4, maxLen = 8)

        assertEquals(1, exported.size)
    }

    private fun twoIntVars(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = emptyList(),
    )

    @Test
    fun `exported int-atom literals should round-trip through import into another session`() {
        val src = PropagationSession(twoIntVars())
        val clause = Clause(intArrayOf(src.boundGeLit(0, 3, true), src.boundLeLit(1, 2, true)))
        src.addLearnedClause(clause, lbd = 2)
        val exported = src.exportGlueClauses(maxLbd = 10, maxLen = 10)
        assertEquals(1, exported.size)

        val dst = PropagationSession(twoIntVars())
        assertEquals(0, dst.learnedClauseCount)
        dst.importClause(exported.single())
        assertEquals(1, dst.learnedClauseCount)

        assertEquals(exported.single().key, dst.exportGlueClauses(10, 10).single().key)
    }

    @Test
    fun `glue filter should drop high-LBD and long clauses`() {
        val s = PropagationSession(twoIntVars())
        s.addLearnedClause(Clause(intArrayOf(s.boundGeLit(0, 3, true), s.boundLeLit(1, 2, true))), lbd = 9)
        assertTrue(s.exportGlueClauses(maxLbd = 4, maxLen = 10).isEmpty())
        assertEquals(1, s.exportGlueClauses(maxLbd = 9, maxLen = 10).size)
        assertTrue(s.exportGlueClauses(maxLbd = 9, maxLen = 1).isEmpty())
    }
}
