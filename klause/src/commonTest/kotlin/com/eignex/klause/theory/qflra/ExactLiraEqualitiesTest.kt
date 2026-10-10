package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.exactForm
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExactLiraEqualitiesTest {
    @Test
    fun `guarded equality offsets imply a comparison over unbounded columns and retract`() {
        val open = Bits(3).also { for (variable in 0..2) it.set(variable) }
        val source = Problem(
            2,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(-2, 2), intArrayOf(0, 1), LinearOp.EQ, -2),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 2),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 3),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertEquals(true, session.boolValue(1))
            assertEquals(setOf(Lit.make(0, false), Lit.make(1, true)), session.reasonFor(1)?.literals?.toSet())
            session.popTo(0)
            assertNull(session.boolValue(1))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            assertNull(session.boolValue(1))
        }
    }

    @Test
    fun `larger comparisons cancel within open equality components and retract`() {
        for ((operator, bound, truth) in listOf(
            Triple(LinearOp.EQ, 3, true), Triple(LinearOp.NE, 3, false),
            Triple(LinearOp.LE, 2, false), Triple(LinearOp.GE, 3, true),
        )) {
            val open = Bits(4).also { for (variable in 0..3) it.set(variable) }
            val source = Problem(
                3,
                intBounds = IntBounds.fromModelBounds(LongArray(4), LongArray(4), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.EQ, 2),
                    ReifiedLinear(2, intArrayOf(1, -1, 1, -1), intArrayOf(0, 1, 2, 3), operator, bound),
                ),
            )
            ExactLiraSearchComponent(source).use { component ->
                val session = SearchSession(listOf(component))
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

                assertEquals(truth, session.boolValue(2))
                assertEquals(
                    setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, truth)),
                    session.reasonFor(2)?.literals?.toSet(),
                )
                session.popTo(0)
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
                assertNull(session.boolValue(2))
            }
        }
    }

    @Test
    fun `a conflicting larger comparison cites each component equality guard`() {
        val open = Bits(4).also { for (variable in 0..3) it.set(variable) }
        val source = Problem(
            3,
            intBounds = IntBounds.fromModelBounds(LongArray(4), LongArray(4), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.EQ, 2),
                ReifiedLinear(2, intArrayOf(1, -1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 4),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        for (variable in 0..2) session.push(SearchDecision.Bool(Lit.make(variable, true)))

        val conflict = assertIs<ComponentResult.Conflict>(propagation.propagate(session, Cancellation.Never))

        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false)),
            assertNotNull(conflict.explanation).literals.toSet(),
        )
    }

    @Test
    fun `larger comparisons preserve declared fixed values when the forest root is displaced`() {
        val open = Bits(4).also { it.set(1); it.set(2) }
        val source = Problem(
            2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(7, 0, 0, 0), longArrayOf(7, 0, 0, 0), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, -5),
                ReifiedLinear(1, intArrayOf(1, 1, -1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 2),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(0, true)))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(true, session.boolValue(1))
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, true)), session.reasonFor(1)?.literals?.toSet())
    }

    @Test
    fun `contradictory equality and disequality guards cite the active offset chain`() {
        for ((operator, bound) in listOf(LinearOp.EQ to 2, LinearOp.NE to 3)) {
            val open = Bits(3).also { for (variable in 0..2) it.set(variable) }
            val source = Problem(
                2,
                intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                    Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 2),
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 2), operator, bound),
                ),
            )
            val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
                factor.linearRows.map { it.exactForm(0) }
            }) { _, _ -> ComponentResult.Consistent }
            val session = SearchSession(emptyList())
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            session.push(SearchDecision.Bool(Lit.make(1, true)))

            val conflict = assertIs<ComponentResult.Conflict>(propagation.propagate(session, Cancellation.Never))

            assertEquals(
                setOf(Lit.make(0, false), Lit.make(1, false)), assertNotNull(conflict.explanation).literals.toSet(),
            )
        }
    }

    @Test
    fun `a nonintegral equality threshold implies its guard is false`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(false, session.boolValue(0))
        assertContentEquals(intArrayOf(Lit.make(0, false)), assertNotNull(session.reasonFor(0)).literals)
    }

    @Test
    fun `cancellation during equality explanation withholds its implication`() {
        for (metered in listOf(false, true)) {
            val open = Bits(2).also { it.set(0); it.set(1) }
            val source = Problem(
                2,
                intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                ),
            )
            val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
                factor.linearRows.map { it.exactForm(0) }
            }) { _, _ -> ComponentResult.Consistent }
            val session = SearchSession(emptyList())
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            var cancelled = false
            val context = object : SearchContext by session {
                override fun atomLiteral(decision: SearchDecision): Int? {
                    if (decision == SearchDecision.Bool(Lit.make(1, true))) cancelled = true
                    return session.atomLiteral(decision)
                }
            }
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = cancelled
                override fun workMeter(): WorkMeter? = if (metered) WorkMeter { } else null
            }

            assertIs<ComponentResult.Indeterminate>(propagation.propagate(context, stop))

            assertNull(session.boolValue(1))
        }
    }

    @Test
    fun `wide equalities do not imply a comparison through wrapped offsets`() {
        val open = Bits(3).also { for (variable in 0..2) it.set(variable) }
        val source = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, Long.MAX_VALUE),
                Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, Long.MAX_VALUE),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, -2),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertNull(session.boolValue(0))
    }
}
