package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BoundedVariableElimination
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateRebuildWarmClausesTest {
    @Test
    fun `complementing source coordinates should preserve owned consequences`() {
        for (complemented in listOf(0, 1)) for (variable in listOf(0, 1)) {
            val learned = Clause(intArrayOf(Lit.make(variable, true), 4))
            val source = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 3)), learned,
            )).bake()
            val session = PropagationSession(source)
            session.addLearnedClause(learned, 2)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session,
                SourceRebuilds(listOf(RebuildStep.CopyLiteral(1, 0))), arrayOfNulls(3),
                1_048_576L, 65_536L, Cancellation.Never, complemented))

            assertContentEquals(intArrayOf(Lit.make(0, variable != complemented), 4),
                (warm.clauses.single() as Clause).literals)
        }
    }

    @Test
    fun `equivalent literal reconstruction should translate owned learning in either polarity`() {
        for (replacement in listOf(2, 3)) for (native in listOf(false, true)) {
            val source = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, replacement)), Clause(intArrayOf(0, Lit.negate(replacement))),
                Clause(intArrayOf(1, 4)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(1, 4)), 2)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session,
                SourceRebuilds(listOf(RebuildStep.CopyLiteral(0, replacement))), arrayOfNulls(3),
                1_048_576L, 65_536L, Cancellation.Never))

            assertContentEquals(intArrayOf(Lit.negate(replacement), 4), (warm.clauses.single() as Clause).literals)
            assertEquals(1, warm.translated)
            for (mask in 0 until 8) {
                val point = BooleanArray(3) { mask and (1 shl it) != 0 }
                if (source.factors.all { factor ->
                        (factor as Clause).literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    }) {
                    assertTrue((warm.clauses.single() as Clause).literals.any {
                        Lit.evaluate(it, point[Lit.variable(it)])
                    })
                }
            }
        }
    }

    @Test
    fun `only owned consequences should enter the private generation`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 2)), Clause(intArrayOf(4, 6)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 2, origin = 1)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(listOf(
                RebuildStep.SatisfyClauses(0, listOf(intArrayOf(0, 2), intArrayOf(1, 2))),
            )), arrayOfNulls(4), 1_048_576L, 65_536L, Cancellation.Never))

            assertEquals(2, warm.clauses.size)
            val copied = warm.clauses.first() as Clause
            assertContentEquals(intArrayOf(4, 6), copied.literals)
            assertContentEquals(intArrayOf(2), (warm.clauses.last() as Clause).literals)
            assertEquals(1, warm.translated)
            assertNotSame(session.learnedClauseLiterals(0), copied.literals)
            assertEquals(3, session.learnedClauseCount)
        }
    }

    @Test
    fun `reclaimed learning should be excluded from a fresh snapshot`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(4, 6)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 2)
            session.forgetLearnedClauses { index, _ -> index == 1 }

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(emptyList()),
                arrayOfNulls(4), 1_048_576L, 65_536L, Cancellation.Never))

            assertContentEquals(intArrayOf(4, 6), (warm.clauses.single() as Clause).literals)
        }
    }

    @Test
    fun `logically retired clauses should be excluded from a fresh snapshot`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(4, 6)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 9)
            assertEquals(1, session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { it == 1 })

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(emptyList()),
                arrayOfNulls(4), 1_048_576L, 65_536L, Cancellation.Never))

            assertEquals(2, session.learnedClauseCount)
            assertContentEquals(intArrayOf(4, 6), (warm.clauses.single() as Clause).literals)
        }
    }

    @Test
    fun `copied consequences should survive canonical forgetting`() {
        for (native in listOf(false, true)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(emptyList()),
                arrayOfNulls(2), 1_048_576L, 65_536L, Cancellation.Never))

            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(0, session.learnedClauseCount)
            assertContentEquals(intArrayOf(0, 2), (warm.clauses.single() as Clause).literals)
        }
    }

    @Test
    fun `learned clauses should project through either pivot polarity without excluding source points`() {
        for (native in listOf(false, true)) for (pivot in listOf(0, 1)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(pivot, 2)), Clause(intArrayOf(Lit.negate(pivot), 4)),
                Clause(intArrayOf(5, 6)),
            )).bake()
            val delta = BoundedVariableElimination.eliminateSource(source, setOf(1, 2, 3))
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(Lit.negate(pivot), 6)), 2)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, delta.rebuild,
                arrayOfNulls(4), 1_048_576L, 65_536L, Cancellation.Never))

            assertContentEquals(intArrayOf(6, 2), (warm.clauses.single() as Clause).literals)
            assertEquals(1, warm.translated)
            for (mask in 0 until 16) {
                val point = Sample(BooleanArray(4) { mask and (1 shl it) != 0 }, longArrayOf())
                if (source.factors.all { factor ->
                        (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
                    }) {
                    assertTrue(warm.clauses.all { factor ->
                        (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
                    })
                }
            }
        }
    }

    @Test
    fun `pure pivot choices should simplify learned consequences`() {
        for (native in listOf(false, true)) for (pivot in listOf(0, 1)) {
            val source = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(pivot, 2)), Clause(intArrayOf(3, 4)),
            )).bake()
            val delta = BoundedVariableElimination.eliminateSource(source, setOf(1, 2))
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(Lit.negate(pivot), 3, 4)), 2)
            session.addLearnedClause(Clause(intArrayOf(pivot, 2, 4)), 2)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, delta.rebuild,
                arrayOfNulls(3), 1_048_576L, 65_536L, Cancellation.Never))

            assertContentEquals(intArrayOf(3, 4), (warm.clauses.single() as Clause).literals)
            assertEquals(1, warm.translated)
        }
    }

    @Test
    fun `learned projection should follow the order of successive eliminations`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)),
            Clause(intArrayOf(3, 6)), Clause(intArrayOf(5, 6)),
        )).bake()
        val delta = BoundedVariableElimination.eliminateSource(source, setOf(2, 3))
        val session = PropagationSession(source)
        session.addLearnedClause(Clause(intArrayOf(1, 6)), 2)

        val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, delta.rebuild,
            arrayOfNulls(4), 1_048_576L, 65_536L, Cancellation.Never))

        assertEquals(2, delta.rebuild.steps.size)
        assertContentEquals(intArrayOf(6), (warm.clauses.single() as Clause).literals)
        for (mask in 0 until 16) {
            val point = Sample(BooleanArray(4) { mask and (1 shl it) != 0 }, longArrayOf())
            if (source.factors.all { factor ->
                    (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
                }) {
                assertTrue((warm.clauses.single() as Clause).literals.any {
                    Lit.evaluate(it, point.boolValue(Lit.variable(it)))
                })
            }
        }
    }

    @Test
    fun `a tautological resolvent should contribute no copied learning`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)),
        )).bake()
        val delta = BoundedVariableElimination.eliminateSource(source, setOf(1, 2))
        val session = PropagationSession(source)
        session.addLearnedClause(Clause(intArrayOf(0, 2, 5)), 2)

        val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, delta.rebuild,
            arrayOfNulls(3), 1_048_576L, 65_536L, Cancellation.Never))

        assertTrue(warm.clauses.isEmpty())
        assertEquals(0, warm.translated)
    }

    @Test
    fun `insufficient projection scratch should preserve compatible retained learning`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(5, 6)),
        )).bake()
        val delta = BoundedVariableElimination.eliminateSource(source, setOf(1, 2, 3))
        val session = PropagationSession(source)
        session.addLearnedClause(Clause(intArrayOf(1, 6)), 2)
        session.addLearnedClause(Clause(intArrayOf(2, 6)), 2)

        val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, delta.rebuild,
            arrayOfNulls(4), 2048L, 65_536L, Cancellation.Never))

        assertContentEquals(intArrayOf(2, 6), (warm.clauses.single() as Clause).literals)
        assertEquals(0L, warm.projectionScratchBytes)
        assertEquals(1536L, warm.modeledBytes)
    }

    @Test
    fun `a wide selected resolvent should preserve other compatible learning`() {
        val source = Problem(66, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1) + (2 until 34).map { it * 2 }.toIntArray()),
        )).bake()
        val delta = BoundedVariableElimination.eliminateSource(source, (1 until 66).toSet())
        val session = PropagationSession(source)
        val retained = intArrayOf(2) + (2 until 34).map { it * 2 }.toIntArray()
        session.addLearnedClause(Clause(intArrayOf(0, 2) + (34 until 66).map { it * 2 }.toIntArray()), 2)
        session.addLearnedClause(Clause(retained), 2)

        val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, delta.rebuild,
            arrayOfNulls(66), 1_048_576L, 65_536L, Cancellation.Never))

        assertContentEquals(retained, (warm.clauses.single() as Clause).literals)
        assertEquals(0, warm.translated)
    }

    @Test
    fun `cancellation during eliminated clause transfer should withhold the snapshot`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(5, 6)),
        )).bake()
        val delta = BoundedVariableElimination.eliminateSource(source, setOf(1, 2, 3))
        val session = PropagationSession(source)
        session.addLearnedClause(Clause(intArrayOf(1, 6)), 2)
        var spent = 0L
        val meter = WorkMeter { spent += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 40L
            override fun workMeter(): WorkMeter = meter
        }

        val warm = PrivateRebuildWarmClauses.capture(session, delta.rebuild, arrayOfNulls(4),
            1_048_576L, 65_536L, token)

        assertNull(warm)
        assertEquals(1, session.learnedClauseCount)
    }

    @Test
    fun `globally fixed false literals should be removed from retained learned clauses`() {
        for (native in listOf(false, true)) {
            val source = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0)), Clause(intArrayOf(2, 4)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(1, 2, 4)), 2)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(emptyList()),
                Array(3) { session.boolValue(it) }, 1_048_576L, 65_536L, Cancellation.Never))

            assertContentEquals(intArrayOf(2, 4), (warm.clauses.single() as Clause).literals)
            assertEquals(2L, warm.literals)
            assertEquals(1536L, warm.modeledBytes)
        }
    }

    @Test
    fun `insufficient transfer grants should preserve the generation without copied learning`() {
        for ((bytes, work) in listOf(0L to 65_536L, 1_048_576L to 0L, 1_048_576L to 17L)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()
            val session = PropagationSession(source)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(emptyList()),
                arrayOfNulls(2), bytes, work, Cancellation.Never))

            assertEquals(0, warm.clauses.size)
            assertEquals(0L, warm.modeledBytes)
        }
    }

    @Test
    fun `containing cancellation should withhold a learned snapshot`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()

        val warm = PrivateRebuildWarmClauses.capture(PropagationSession(source), SourceRebuilds(emptyList()),
            arrayOfNulls(2), 1_048_576L, 65_536L, Cancellation { true })

        assertNull(warm)
    }
}
