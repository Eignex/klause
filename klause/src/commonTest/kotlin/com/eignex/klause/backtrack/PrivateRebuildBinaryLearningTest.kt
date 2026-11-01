package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrivateRebuildBinaryLearningTest {
    @Test
    fun `owned reciprocal implications should capture a source bound equivalence without root pins`() {
        for (replacement in listOf(2, 3)) for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, replacement, 4)), Clause(intArrayOf(1, replacement, 5)),
                Clause(intArrayOf(0, Lit.negate(replacement), 6)), Clause(intArrayOf(0, Lit.negate(replacement), 7)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(1, replacement)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, Lit.negate(replacement))), 2)

            val captured = assertNotNull(PrivateRebuildBinaryLearning.capture(session, longArrayOf(), 0) { true })

            assertSame(source, captured.source)
            assertNull(session.boolValue(0))
            assertNull(session.boolValue(1))
            assertNotSame(session.learnedClauseLiterals(0), captured.clauses[0].literals)
            for (mask in 0 until 16) {
                val point = BooleanArray(4) { mask and (1 shl it) != 0 }
                if (source.factors.all { factor ->
                        (factor as Clause).literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    }) {
                    assertTrue(captured.clauses.all { clause ->
                        clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    })
                }
            }
        }
    }

    @Test
    fun `an owned binary should close a reciprocal source implication`() {
        for (replacement in listOf(2, 3)) {
            val source = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, replacement)), Clause(intArrayOf(0, Lit.negate(replacement), 4)),
                Clause(intArrayOf(0, Lit.negate(replacement), 5)),
            )).bake()
            val session = PropagationSession(source)
            session.addLearnedClause(Clause(intArrayOf(0, Lit.negate(replacement))), 2)

            val captured = PrivateRebuildBinaryLearning.capture(session, longArrayOf(), 0) { true }

            assertNotNull(captured)
        }
    }

    @Test
    fun `recent binaries should close a source implication within a linear scan grant`() {
        val learning = (2 until 82).map { Clause(intArrayOf(1, Lit.make(it, true))) }
        val source = Problem(82, 0, emptyArray(), (listOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 3, 4)), Clause(intArrayOf(0, 3, 5)),
        ) + learning).toTypedArray()).bake()
        val session = PropagationSession(source)
        session.addLearnedClause(Clause(intArrayOf(0, 3)), 2)
        for (clause in learning) session.addLearnedClause(clause, 2)
        var spent = 0L

        val captured = PrivateRebuildBinaryLearning.capture(session, longArrayOf(), 0) { work ->
            if (work > 4096L - spent) false else { spent += work; true }
        }

        assertNotNull(captured)
        assertTrue(spent <= 4096L)
    }

    @Test
    fun `foreign and scoped learning should withhold an equivalence`() {
        for (native in listOf(false, true)) for (permanent in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, 2, 4)), Clause(intArrayOf(1, 2, 5)),
                Clause(intArrayOf(0, 3, 6)), Clause(intArrayOf(0, 3, 7)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(1, 2)), 2)
            if (permanent) session.addLearnedClause(Clause(intArrayOf(0, 3)), 2, permanent = true)
            else session.addLearnedClause(Clause(intArrayOf(0, 3)), 2, origin = 1)

            val captured = PrivateRebuildBinaryLearning.capture(session, longArrayOf(), 0) { true }

            assertNull(captured)
        }
    }

    @Test
    fun `an attempted relation should not trigger another generation`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 3, 4)), Clause(intArrayOf(0, 3, 5)),
        )).bake()
        val session = PropagationSession(source)
        session.addLearnedClause(Clause(intArrayOf(0, 3)), 2)
        val first = assertNotNull(PrivateRebuildBinaryLearning.capture(session, longArrayOf(), 0) { true })

        val repeated = PrivateRebuildBinaryLearning.capture(session, longArrayOf(first.key), 1) { true }

        assertNull(repeated)
    }

    @Test
    fun `exhausted scan work should preserve canonical learning`() {
        for (allowance in listOf(0L, 128L, 140L)) {
            val source = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 3, 4)), Clause(intArrayOf(0, 3, 5)),
            )).bake()
            val session = PropagationSession(source)
            session.addLearnedClause(Clause(intArrayOf(0, 3)), 2)
            var spent = 0L

            val captured = PrivateRebuildBinaryLearning.capture(session, longArrayOf(), 0) { work ->
                if (work > allowance - spent) false else { spent += work; true }
            }

            assertNull(captured)
            assertTrue(spent <= allowance)
            assertEquals(1, session.learnedClauseCount)
            assertEquals(3, source.numFactors)
        }
    }
}
