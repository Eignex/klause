package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactIntegerEqualityPropagationTest {
    @Test
    fun `one equality snapshot should publish every comparison it proves`() {
        val model = Problem(
            numBoolVars = 4,
            intBounds = IntBounds.fromModelBounds(longArrayOf(-100), longArrayOf(100), null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.NE, 3),
                ReifiedLinear(3, intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(4))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(listOf(true, false, false), (1..3).map(session::boolValue))
        for (variable in 1..3) {
            val value = assertNotNull(session.boolValue(variable))
            assertEquals(setOf(1, Lit.make(variable, value)), session.reasonFor(variable)?.literals?.toSet())
        }
    }

    @Test
    fun `a stop after one publication should withhold the remaining comparisons`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(longArrayOf(-100), longArrayOf(100), null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.NE, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
        var cancelled = false

        val result = propagation.propagate(session, Cancellation { cancelled }) {
            cancelled = true
            ComponentResult.Consistent
        }

        assertIs<ComponentResult.Indeterminate>(result)
        assertEquals(true, session.boolValue(1))
        assertNull(session.boolValue(2))
    }

    @Test
    fun `out of range binary coordinates cannot use the zero sentinel`() {
        val source = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)
        val factor = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(
                LinearRow.ofInts(intArrayOf(0, 1), longArrayOf(1, -1), LinearOp.EQ, 0),
            ))
        }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(-100), longArrayOf(100), null, null),
            factors = arrayOf(factor),
        )

        val propagation = ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never)

        assertNull(propagation)
    }

    @Test
    fun `signed affine chains explain each comparison with their active guards`() {
        for ((relation, expected) in listOf(
            LinearOp.EQ to true, LinearOp.NE to false, LinearOp.LE to true, LinearOp.GE to true,
        )) {
            val model = Problem(
                numBoolVars = 4,
                intBounds = IntBounds.fromModelBounds(
                    longArrayOf(-100, -100, -100), longArrayOf(100, 100, 100), null, null,
                ),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
                    ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.EQ, 4),
                    ReifiedLinear(2, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 2),
                    ReifiedLinear(3, intArrayOf(1), intArrayOf(0), relation, 5),
                ),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(4))
            session.initialize()
            repeat(3) { session.push(SearchDecision.Bool(Lit.make(it, true))) }
            val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

            assertEquals(expected, session.boolValue(3))
            assertEquals(setOf(1, 3, 5, Lit.make(3, expected)), session.reasonFor(3)?.literals?.toSet())
        }
    }

    @Test
    fun `a false disequality supplies its negative guard premise`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(-100), longArrayOf(100), null, null,
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(-1), intArrayOf(0), LinearOp.NE, -7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        session.initialize()
        session.push(SearchDecision.Bool(Lit.make(0, false)))
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never)

        assertEquals(true, session.boolValue(1))
        assertEquals(setOf(0, 2), session.reasonFor(1)?.literals?.toSet())
    }

    @Test
    fun `retraction discards descendant equalities before a sibling query`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(-100, -100), longArrayOf(100, 100), null, null,
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 4),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
        propagation.propagate(session, Cancellation.Never)
        assertEquals(true, session.boolValue(2))

        session.popTo(1)
        session.push(SearchDecision.Bool(3))
        propagation.propagate(session, Cancellation.Never)

        assertNull(session.boolValue(2))
    }

    @Test
    fun `an unanchored equality proves relative comparisons without choosing coordinates`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(-100, -100, -100), longArrayOf(100, 100, 100), null, null,
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 7),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))

        assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
            .propagate(session, Cancellation.Never)

        assertEquals(true, session.boolValue(2))
        assertEquals(setOf(1, 3, 4), session.reasonFor(2)?.literals?.toSet())
    }

    @Test
    fun `signed unanchored chains preserve the sum comparison`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(-100, -100, -100), longArrayOf(100, 100, 100), null, null,
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 4),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.EQ, -1),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))

        assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
            .propagate(session, Cancellation.Never)

        assertEquals(true, session.boolValue(2))
        assertEquals(setOf(1, 3, 4), session.reasonFor(2)?.literals?.toSet())
    }

    @Test
    fun `an unanchored chain does not choose an absolute coordinate`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(-100, -100), longArrayOf(100, 100), null, null,
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        session.initialize()
        session.push(SearchDecision.Bool(0))

        assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
            .propagate(session, Cancellation.Never)

        assertNull(session.boolValue(1))
    }

    @Test
    fun `a disjunction alternative supplies no equality premise`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(-100, -100), longArrayOf(100, 100), null, null,
            ),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.EQ, LinearOp.EQ), longArrayOf(2, 3)),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        session.initialize()

        assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
            .propagate(session, Cancellation.Never)

        assertNull(session.boolValue(0))
    }

    @Test
    fun `overflowing translations supply no wrapped comparison`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE), longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), null, null,
            ),
            factors = arrayOf(
                ReifiedLinear(0, longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, Long.MAX_VALUE),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                ReifiedLinear(2, longArrayOf(1), intArrayOf(0), LinearOp.EQ, Long.MIN_VALUE),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))

        assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
            .propagate(session, Cancellation.Never)

        assertNull(session.boolValue(2))
    }

    @Test
    fun `a containing stop withholds deductions and charges visited work`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(-100), longArrayOf(100), null, null,
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged > 2
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val result = propagation.propagate(session, stop)

        assertIs<ComponentResult.Indeterminate>(result)
        assertNull(session.boolValue(1))
        assertTrue(charged > 2)
    }

    @Test
    fun `the exact linear component publishes equality consequences at a partial fixpoint`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(-100, -100), longArrayOf(100, 100), null, null,
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            component.observeCompleteTraversal()
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(2)))

            assertEquals(true, session.boolValue(2))
            assertEquals(setOf(1, 3, 4), session.reasonFor(2)?.literals?.toSet())
        }
    }
    @Test
    fun `aliased exclusions should prove strict bounds in original source coordinates`() {
        for (upper in listOf(true, false)) {
            for (negativeGuards in listOf(true, false)) {
                val model = Problem(
                    numBoolVars = 4,
                    intBounds = IntBounds.fromModelBounds(LongArray(4) { -100 }, LongArray(4) { 100 }, null, null),
                    factors = arrayOf(
                        ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1),
                            if (negativeGuards) LinearOp.EQ else LinearOp.NE, 7),
                        ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(2, 3),
                            if (upper != negativeGuards) LinearOp.LE else LinearOp.GE,
                            if (!negativeGuards) 5 else if (upper) 6 else 4),
                        ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 3),
                        ReifiedLinear(3, intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.EQ, 1),
                    ),
                )
                val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(4))
                session.initialize()
                for (literal in listOf(Lit.make(0, !negativeGuards), Lit.make(1, !negativeGuards), 4, 6)) {
                    session.push(SearchDecision.Bool(literal))
                }
                val received = ArrayList<ExactIntegerEqualityPropagation.Order>()
                val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

                val result = propagation.propagate(session, Cancellation.Never,
                    orderEligible = { _, _, _ -> true },
                    receiveOrder = { received += it; ComponentResult.Consistent })

                assertIs<ComponentResult.Consistent>(result)
                val order = received.single()
                assertEquals(listOf(0 to 1L, 1 to -1L), order.terms)
                assertEquals(if (upper) 6L else 8L, order.bound)
                assertEquals(upper, order.upper)
                assertEquals(setOf(Lit.make(0, negativeGuards), Lit.make(1, negativeGuards), 5, 7),
                    order.premises.toSet())
            }
        }
    }

    @Test
    fun `signed aliases should reverse a derived source bound direction`() {
        for (upper in listOf(true, false)) {
            val model = Problem(
                numBoolVars = 4,
                intBounds = IntBounds.fromModelBounds(LongArray(4) { -100 }, LongArray(4) { 100 }, null, null),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.NE, 2),
                    ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(2, 3),
                        if (upper) LinearOp.GE else LinearOp.LE, 1),
                    ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.EQ, 4),
                    ReifiedLinear(3, intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.EQ, 1),
                ),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(4))
            session.initialize()
            for (literal in listOf(0, 2, 4, 6)) session.push(SearchDecision.Bool(literal))
            val received = ArrayList<ExactIntegerEqualityPropagation.Order>()
            val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

            propagation.propagate(session, Cancellation.Never,
                orderEligible = { _, _, _ -> true },
                receiveOrder = { received += it; ComponentResult.Consistent })

            val order = received.single()
            assertEquals(listOf(0 to 1L, 1 to -1L), order.terms)
            assertEquals(if (upper) 1L else 3L, order.bound)
            assertEquals(upper, order.upper)
            assertEquals(setOf(1, 3, 5, 7), order.premises.toSet())
        }
    }

    @Test
    fun `a unary alias consequence should retain its original source variable`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
        val received = ArrayList<ExactIntegerEqualityPropagation.Order>()
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { received += it; ComponentResult.Consistent })

        val order = received.single()
        assertEquals(listOf(0 to 1L), order.terms)
        assertEquals(6L, order.bound)
        assertEquals(setOf(1, 3, 5), order.premises.toSet())
    }

    @Test
    fun `an anchored alias should not replace a source term with the zero sentinel`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
        val received = ArrayList<ExactIntegerEqualityPropagation.Order>()
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { received += it; ComponentResult.Consistent })

        val order = received.single()
        assertEquals(listOf(0 to 1L, 1 to 1L), order.terms)
        assertEquals(6L, order.bound)
        assertEquals(setOf(1, 3, 5), order.premises.toSet())
    }

    @Test
    fun `unconditional aliases should add no guard premise to a source bound`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))
        val received = ArrayList<ExactIntegerEqualityPropagation.Order>()
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { received += it; ComponentResult.Consistent })

        assertEquals(setOf(1, 3), received.single().premises.toSet())
    }

    @Test
    fun `equivalent raw source expressions should remain with ordinary side selection`() {
        for (reversed in listOf(true, false)) {
            val model = Problem(
                numBoolVars = 2,
                intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.NE, 5),
                    ReifiedLinear(1, intArrayOf(1, -1), if (reversed) intArrayOf(1, 0) else intArrayOf(0, 1),
                        if (reversed) LinearOp.GE else LinearOp.LE, if (reversed) -5 else 5),
                ),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
            session.initialize()
            session.push(SearchDecision.Bool(0))
            session.push(SearchDecision.Bool(2))
            var received = 0
            val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

            propagation.propagate(session, Cancellation.Never,
                orderEligible = { _, _, _ -> true },
                receiveOrder = { received++; ComponentResult.Consistent })

            assertEquals(0, received)
        }
    }

    @Test
    fun `a side already owned by arithmetic should supply no alias order candidate`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
        var received = 0
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> false },
            receiveOrder = { received++; ComponentResult.Consistent })

        assertEquals(0, received)
    }

    @Test
    fun `a source bound should require both asserted endpoint facts`() {
        for ((excluded, ordered) in listOf(false to false, true to false, false to true)) {
            val model = Problem(
                numBoolVars = 3,
                intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                    ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
            session.initialize()
            session.push(SearchDecision.Bool(4))
            if (excluded) session.push(SearchDecision.Bool(0))
            if (ordered) session.push(SearchDecision.Bool(2))
            var received = 0
            val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

            propagation.propagate(session, Cancellation.Never,
                orderEligible = { _, _, _ -> true },
                receiveOrder = { received++; ComponentResult.Consistent })

            assertEquals(0, received)
        }
    }

    @Test
    fun `a nonmatching alias endpoint should supply no source bound`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 8),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
        var received = 0
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { received++; ComponentResult.Consistent })

        assertEquals(0, received)
    }

    @Test
    fun `comparison alternatives should supply no asserted exclusion premise`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.NE, LinearOp.NE), longArrayOf(7, 9)),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))
        var received = 0
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { received++; ComponentResult.Consistent })

        assertEquals(0, received)
    }

    @Test
    fun `retracting a source fact should remove its alias order consequence`() {
        for (removed in listOf(0, 2)) {
            val model = Problem(
                numBoolVars = 3,
                intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                    ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
            session.initialize()
            session.push(SearchDecision.Bool(4))
            session.push(SearchDecision.Bool(if (removed == 0) 2 else 0))
            session.push(SearchDecision.Bool(removed))
            var received = 0
            val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
            propagation.propagate(session, Cancellation.Never,
                orderEligible = { _, _, _ -> true },
                receiveOrder = { received++; ComponentResult.Consistent })
            assertEquals(1, received)

            session.popTo(2)
            received = 0
            propagation.propagate(session, Cancellation.Never,
                orderEligible = { _, _, _ -> true },
                receiveOrder = { received++; ComponentResult.Consistent })

            assertEquals(0, received)
        }
    }

    @Test
    fun `retracting an alias should remove its source bound consequence`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
        var received = 0
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { received++; ComponentResult.Consistent })
        assertEquals(1, received)

        session.popTo(2)
        received = 0
        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { received++; ComponentResult.Consistent })

        assertEquals(0, received)
    }

    @Test
    fun `overflowing strict source thresholds should supply no alias order atom`() {
        for (upper in listOf(true, false)) {
            val endpoint = if (upper) Long.MIN_VALUE else Long.MAX_VALUE
            val model = Problem(
                numBoolVars = 3,
                intBounds = IntBounds.fromModelBounds(
                    LongArray(2) { Long.MIN_VALUE }, LongArray(2) { Long.MAX_VALUE }, null, null,
                ),
                factors = arrayOf(
                    ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.NE, endpoint),
                    ReifiedLinear(1, longArrayOf(1), intArrayOf(1),
                        if (upper) LinearOp.LE else LinearOp.GE, endpoint + (if (upper) 1L else -1L)),
                    ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, if (upper) -1 else 1),
                ),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
            session.initialize()
            for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
            var received = 0
            val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

            propagation.propagate(session, Cancellation.Never,
                orderEligible = { _, _, _ -> true },
                receiveOrder = { received++; ComponentResult.Consistent })

            assertEquals(0, received)
        }
    }

    @Test
    fun `a containing stop should withhold a constructed alias endpoint consequence`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
        var cancelled = false
        var received = 0
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never) { event ->
            if (event == "ALIASED_ORDER_ATOM_TIGHTENED") cancelled = true
        })

        val result = propagation.propagate(session, Cancellation { cancelled },
            orderEligible = { _, _, _ -> true },
            receiveOrder = { received++; ComponentResult.Consistent })

        assertIs<ComponentResult.Indeterminate>(result)
        assertEquals(0, received)
    }

    @Test
    fun `an incomplete arithmetic receiver should withhold propagation success`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        val result = propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { ComponentResult.Indeterminate })

        assertIs<ComponentResult.Indeterminate>(result)
    }

    @Test
    fun `a proved source bound should conflict with its held opposite literal`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(0, 2, 4)) session.push(SearchDecision.Bool(literal))
        val terms = listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ofLong(1L)))
        val atom = assertNotNull(SourceBoundAtom.integerSplit(session, terms, BigFraction.ofLong(6L)))
        val literal = assertNotNull(session.atomLiteral(SearchDecision.Theory(atom.positive)))
        session.push(SearchDecision.Theory(atom.negative))
        val level = session.decisionLevel
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))

        val result = propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { order -> session.imply(literal, SearchExplanation(order.premises + literal)) })

        val conflict = assertIs<ComponentResult.Conflict>(result)
        assertEquals(setOf(1, 3, 5, literal), assertNotNull(conflict.explanation).literals.toSet())
        assertEquals(level, session.decisionLevel)
    }

    @Test
    fun `retracted source bound implications should reuse their registered name`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        for (literal in listOf(4, 2, 0)) session.push(SearchDecision.Bool(literal))
        val terms = listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ofLong(1L)))
        val atom = assertNotNull(SourceBoundAtom.integerSplit(session, terms, BigFraction.ofLong(6L)))
        val literal = assertNotNull(session.atomLiteral(SearchDecision.Theory(atom.positive)))
        val propagation = assertNotNull(ExactIntegerEqualityPropagation.prepare(model, Cancellation.Never))
        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { order -> session.imply(literal, SearchExplanation(order.premises + literal)) })
        assertEquals(Lit.isPositive(literal), session.boolValue(Lit.variable(literal)))

        session.popTo(2)
        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { order -> session.imply(literal, SearchExplanation(order.premises + literal)) })
        assertNull(session.boolValue(Lit.variable(literal)))
        session.push(SearchDecision.Bool(0))
        propagation.propagate(session, Cancellation.Never,
            orderEligible = { _, _, _ -> true },
            receiveOrder = { order -> session.imply(literal, SearchExplanation(order.premises + literal)) })

        val repeated = assertNotNull(SourceBoundAtom.integerSplit(session, terms, BigFraction.ofLong(6L)))
        assertEquals(literal, session.atomLiteral(SearchDecision.Theory(repeated.positive)))
        assertEquals(Lit.isPositive(literal), session.boolValue(Lit.variable(literal)))
        assertEquals(setOf(1, 3, 5, literal), session.reasonFor(Lit.variable(literal))?.literals?.toSet())
        assertEquals(3, session.decisionLevel)
    }

    @Test
    fun `complete production openings should publish and receive alias order atoms`() {
        for (upper in listOf(true, false)) {
            val model = Problem(
                numBoolVars = 4,
                intBounds = IntBounds.fromModelBounds(LongArray(4) { -100 }, LongArray(4) { 100 }, null, null),
                factors = arrayOf(
                    Clause(intArrayOf(0)), Clause(intArrayOf(2)), Clause(intArrayOf(4)), Clause(intArrayOf(6)),
                    ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.NE, 7),
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(2, 3),
                        if (upper) LinearOp.LE else LinearOp.GE, 5),
                    ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 3),
                    ReifiedLinear(3, intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.EQ, 1),
                ),
            )
            val stats = SmtStatsSink()
            ExactLiraSearchComponent(model).use { component ->
                component.observeWith(stats)
                component.observeCompleteTraversal()
                val session = SearchSession(
                    listOf(ClauseSearchComponent(model.factors.filterIsInstance<Clause>()), component),
                    atoms = SearchAtomRegistry(4),
                )
                assertIs<ComponentResult.Consistent>(session.initialize())

                component.nextBranch(session)

                val terms = listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ofLong(1L)),
                    SourceBoundTerm(SearchIntValue(1), BigFraction.ofLong(-1L)))
                val atom = assertNotNull(SourceBoundAtom.integerSplit(session, terms,
                    BigFraction.ofLong(if (upper) 6L else 7L)))
                val decision = SearchDecision.Theory(if (upper) atom.positive else atom.negative)
                val literal = assertNotNull(session.atomLiteral(decision))
                assertEquals(Lit.isPositive(literal), session.boolValue(Lit.variable(literal)))
                assertEquals(setOf(1, 3, 5, 7, literal), session.reasonFor(Lit.variable(literal))?.literals?.toSet())
                assertEquals(0, session.decisionLevel)
                val stages = assertNotNull(stats.snapshot().completeSearchStages)
                assertTrue((stages["ALIASED_ORDER_ATOM_PUBLISHED"] ?: 0L) > 0L)
                assertTrue((stages["ALIASED_ORDER_ATOM_RECEIVED"] ?: 0L) > 0L)
                val published = stages["ALIASED_ORDER_ATOM_PUBLISHED"]
                component.nextBranch(session)
                assertEquals(published, stats.snapshot().completeSearchStages?.get("ALIASED_ORDER_ATOM_PUBLISHED"))
            }
        }
    }

    @Test
    fun `unsupported source atom registration should withhold alias order publication`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2) { -100 }, LongArray(2) { 100 }, null, null),
            factors = arrayOf(
                Clause(intArrayOf(0)), Clause(intArrayOf(2)), Clause(intArrayOf(4)),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.NE, 7),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val session = SearchSession(
                listOf(ClauseSearchComponent(model.factors.filterIsInstance<Clause>()), component),
            )
            session.initialize()

            component.nextBranch(session)

            val stages = assertNotNull(stats.snapshot().completeSearchStages)
            assertTrue((stages["ALIASED_ORDER_ATOM_REGISTER_DECLINED"] ?: 0L) > 0L)
            assertEquals(0L, stages["ALIASED_ORDER_ATOM_PUBLISHED"] ?: 0L)
        }
    }
}
