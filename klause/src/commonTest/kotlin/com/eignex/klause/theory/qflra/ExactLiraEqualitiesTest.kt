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
    fun `negative signed comparisons preserve their direction and complemented source reasons`() {
        for ((operator, bound, truth) in listOf(
            Triple(LinearOp.LE, 0, false), Triple(LinearOp.GE, 0, true),
            Triple(LinearOp.LE, 4, true), Triple(LinearOp.GE, 4, false),
            Triple(LinearOp.EQ, 2, true), Triple(LinearOp.NE, 2, false),
        )) {
            val open = Bits(3).also { for (variable in 0..2) it.set(variable) }
            val source = Problem(
                3,
                intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 3),
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 4),
                    ReifiedLinear(2, intArrayOf(-2, -2), intArrayOf(0, 2), operator, bound),
                ),
            )
            val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
                factor.linearRows.map { it.exactForm(0) }
            }) { _, _ -> ComponentResult.Consistent }
            val session = SearchSession(emptyList())
            session.push(SearchDecision.Bool(Lit.make(0, false)))
            session.push(SearchDecision.Bool(Lit.make(1, true)))

            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

            assertEquals(truth, session.boolValue(2))
            assertEquals(
                setOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, truth)),
                session.reasonFor(2)?.literals?.toSet(),
            )
        }
    }

    @Test
    fun `a signed disequality path excludes a translated comparison and retracts`() {
        val open = Bits(4).also { for (variable in 0..3) it.set(variable) }
        val source = Problem(
            4,
            intBounds = IntBounds.fromModelBounds(LongArray(4), LongArray(4), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.EQ, 4),
                ReifiedLinear(2, intArrayOf(-1, 1), intArrayOf(1, 3), LinearOp.NE, -2),
                ReifiedLinear(3, intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.EQ, 5),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        for (variable in 0..2) session.push(SearchDecision.Bool(Lit.make(variable, true)))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(false, session.boolValue(3))
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false), Lit.make(3, false)),
            session.reasonFor(3)?.literals?.toSet(),
        )
        session.popTo(0)
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        session.push(SearchDecision.Bool(Lit.make(1, true)))
        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
        assertNull(session.boolValue(3))
    }

    @Test
    fun `signed equality paths imply comparisons with guarded reasons and retract`() {
        for ((operator, truth) in listOf(LinearOp.EQ to true, LinearOp.NE to false)) {
            val open = Bits(3).also { for (variable in 0..2) it.set(variable) }
            val source = Problem(
                3,
                intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(-2, -2), intArrayOf(0, 1), LinearOp.EQ, -6),
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 4),
                    ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 2), operator, -1),
                ),
            )
            val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
                factor.linearRows.map { it.exactForm(0) }
            }) { _, _ -> ComponentResult.Consistent }
            val session = SearchSession(emptyList())
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            session.push(SearchDecision.Bool(Lit.make(1, true)))

            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

            assertEquals(truth, session.boolValue(2))
            assertEquals(
                setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, truth)),
                session.reasonFor(2)?.literals?.toSet(),
            )
            session.popTo(0)
            session.push(SearchDecision.Bool(Lit.make(1, true)))
            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
            assertNull(session.boolValue(2))
        }
    }

    @Test
    fun `larger comparisons cancel coefficients through signed equality components`() {
        val open = Bits(4).also { for (variable in 0..3) it.set(variable) }
        val source = Problem(
            3,
            intBounds = IntBounds.fromModelBounds(LongArray(4), LongArray(4), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.EQ, 5),
                ReifiedLinear(2, intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 8),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        session.push(SearchDecision.Bool(Lit.make(1, true)))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(true, session.boolValue(2))
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true)),
            session.reasonFor(2)?.literals?.toSet(),
        )
    }

    @Test
    fun `a sign-changing equality cycle derives an integer fixing with both guards`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        session.push(SearchDecision.Bool(Lit.make(1, true)))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(true, session.boolValue(2))
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true)),
            session.reasonFor(2)?.literals?.toSet(),
        )
    }

    @Test
    fun `a sign-changing equality cycle rejects a nonintegral fixing with both guards`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
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

    @Test
    fun `guarded disequalities exclude comparisons through fixed values and retract`() {
        for ((operator, truth) in listOf(LinearOp.EQ to false, LinearOp.NE to true)) {
            val open = Bits(2).also { it.set(0); it.set(1) }
            val source = Problem(
                3,
                intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7),
                    ReifiedLinear(1, intArrayOf(-2), intArrayOf(1), operator, -14),
                    ReifiedLinear(2, intArrayOf(-3, 3), intArrayOf(0, 1), LinearOp.EQ, 0),
                ),
            )
            val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
                factor.linearRows.map { it.exactForm(0) }
            }) { _, _ -> ComponentResult.Consistent }
            val session = SearchSession(emptyList())
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            session.push(SearchDecision.Bool(Lit.make(1, truth)))

            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

            assertEquals(false, session.boolValue(2))
            assertEquals(
                setOf(Lit.make(0, false), Lit.make(1, !truth), Lit.make(2, false)),
                session.reasonFor(2)?.literals?.toSet(),
            )
            session.popTo(0)
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
            assertNull(session.boolValue(2))
            session.push(SearchDecision.Bool(Lit.make(1, !truth)))
            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
            assertEquals(true, session.boolValue(2))
        }
    }

    @Test
    fun `a disequality excludes a translated comparison across two open components`() {
        val open = Bits(4).also { for (variable in 0..3) it.set(variable) }
        val source = Problem(
            4,
            intBounds = IntBounds.fromModelBounds(LongArray(4), LongArray(4), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.EQ, 4),
                ReifiedLinear(2, intArrayOf(2, -2), intArrayOf(2, 3), LinearOp.NE, 10),
                ReifiedLinear(3, intArrayOf(-1, 1), intArrayOf(1, 0), LinearOp.EQ, 4),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        for (variable in 0..2) session.push(SearchDecision.Bool(Lit.make(variable, true)))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(false, session.boolValue(3))
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false), Lit.make(3, false)),
            session.reasonFor(3)?.literals?.toSet(),
        )
    }

    @Test
    fun `cancellation during a disequality explanation withholds its implication`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.NE, 7),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        session.push(SearchDecision.Bool(Lit.make(1, true)))
        var cancelled = false
        val context = object : SearchContext by session {
            override fun atomLiteral(decision: SearchDecision): Int? {
                if (decision == SearchDecision.Bool(Lit.make(2, false))) cancelled = true
                return session.atomLiteral(decision)
            }
        }

        assertIs<ComponentResult.Indeterminate>(propagation.propagate(context, Cancellation { cancelled }))

        assertNull(session.boolValue(2))
    }

    @Test
    fun `larger equalities derive fixed values with guarded reasons and retract`() {
        for ((operator, truth) in listOf(LinearOp.EQ to true, LinearOp.NE to false)) {
            val open = Bits(2).also { it.set(0); it.set(1) }
            val source = Problem(
                3,
                intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(2, 1), intArrayOf(0, 1), operator, 3),
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                    ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ),
            )
            val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
                factor.linearRows.map { it.exactForm(0) }
            }) { _, _ -> ComponentResult.Consistent }
            val session = SearchSession(emptyList())
            session.push(SearchDecision.Bool(Lit.make(0, truth)))
            session.push(SearchDecision.Bool(Lit.make(1, true)))

            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

            assertEquals(true, session.boolValue(2))
            assertEquals(
                setOf(Lit.make(0, !truth), Lit.make(1, false), Lit.make(2, true)),
                session.reasonFor(2)?.literals?.toSet(),
            )
            session.popTo(0)
            session.push(SearchDecision.Bool(Lit.make(0, !truth)))
            session.push(SearchDecision.Bool(Lit.make(1, true)))
            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
            assertNull(session.boolValue(2))
        }
    }

    @Test
    fun `a nonintegral reduced equality conflicts with its source guards`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 4),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
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

    @Test
    fun `successive larger equalities retain each derivation in an implied value`() {
        val open = Bits(3).also { for (variable in 0..2) it.set(variable) }
        val source = Problem(
            4,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(2, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
                ReifiedLinear(2, intArrayOf(2, 1), intArrayOf(1, 2), LinearOp.EQ, 8),
                ReifiedLinear(3, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 2),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        for (variable in 0..2) session.push(SearchDecision.Bool(Lit.make(variable, true)))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(true, session.boolValue(3))
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false), Lit.make(3, true)),
            session.reasonFor(3)?.literals?.toSet(),
        )
    }

    @Test
    fun `conflicting reduced equalities cite both derived facts and their offset guard`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(3, 2), intArrayOf(0, 1), LinearOp.EQ, 10),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
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
    fun `cancellation during a derived equality explanation withholds its implication`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            ),
        )
        val propagation = ExactLiraEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        session.push(SearchDecision.Bool(Lit.make(1, true)))
        var cancelled = false
        val context = object : SearchContext by session {
            override fun atomLiteral(decision: SearchDecision): Int? {
                if (decision == SearchDecision.Bool(Lit.make(2, true))) cancelled = true
                return session.atomLiteral(decision)
            }
        }

        assertIs<ComponentResult.Indeterminate>(propagation.propagate(context, Cancellation { cancelled }))

        assertNull(session.boolValue(2))
    }

    @Test
    fun `guarded equality offsets and exclusions imply comparisons and retract`() {
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
            assertEquals(false, session.boolValue(1))
            assertEquals(setOf(Lit.make(0, true), Lit.make(1, false)), session.reasonFor(1)?.literals?.toSet())
            session.popTo(0)
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
