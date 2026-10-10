package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.lp.exactForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactLraPropagationTest {
    @Test
    fun `scaled comparisons share activity bounds over open source columns`() {
        val source = Problem(
            2,
            intBounds = IntBounds.fromModelBounds(
                LongArray(2), LongArray(2), Bits(2).also { it.set(0); it.set(1) },
                Bits(2).also { it.set(0); it.set(1) },
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            session.initialize()
            for (truth in listOf(true, false)) {
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, truth))))
                assertEquals(truth, session.boolValue(1))
                assertEquals(
                    setOf(Lit.make(0, !truth), Lit.make(1, truth)),
                    session.reasonFor(1)?.literals?.toSet(),
                )
                session.popTo(0)
                assertNull(session.boolValue(1))
            }
        }
    }

    @Test
    fun `reified bounds propagate through equalities and retract with their premise`() {
        val source = Problem(
            2,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0), longArrayOf(0, 0),
                Bits(2).also { it.set(0); it.set(1) }, Bits(2).also { it.set(0); it.set(1) },
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7),
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 2),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 8),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertEquals(false, session.boolValue(1))

            session.popTo(0)

            assertNull(session.boolValue(1))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertEquals(false, session.boolValue(0))
        }
    }

    @Test
    fun `strict integer bounds round in both coefficient directions`() {
        for ((coefficient, upper, expected) in listOf(Triple(2, true, -1L), Triple(-2, false, 1L))) {
            val source = SmtLib.parse(
                """
                (set-logic QF_LIA)
                (declare-const x Int)
                (assert (< (* $coefficient x) 0))
                (check-sat)
                """.trimIndent(),
            ).model
            LpPropagator(object : LpSearchPolicy {}).use { lp ->
                assertTrue(LiveQfLraSystem(source, lp).install())
                val session = SearchSession(listOf(lp))
                session.initialize()
                val forms = source.factors.map { factor ->
                    factor.linearRows.map { it.exactForm(source.numRealVars) }
                }
                val propagation = ExactLraPropagation(source, lp, LiveQfLraSystem(source, lp), forms)

                assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

                assertEquals(BigFraction.ofLong(expected), lp.state?.activeSide(0, upper)?.side?.number?.value)
            }
        }
    }

    @Test
    fun `strict real endpoints distinguish equality from its complement`() {
        for ((operator, verdict) in listOf("=" to false, "distinct" to true)) {
            val source = SmtLib.parse(
                """
                (set-logic QF_LRA)
                (declare-const x Real)
                (declare-const b Bool)
                (assert (< x 0))
                (assert (= b ($operator x 0)))
                (check-sat)
                """.trimIndent(),
            ).model

            source.componentPlan().search(source, emptyMap()).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                assertEquals(verdict, planned.session.boolValue(0))
            }
        }
    }
}
