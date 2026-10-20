package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
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
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(2)))

            assertEquals(true, session.boolValue(2))
            assertEquals(setOf(1, 3, 4), session.reasonFor(2)?.literals?.toSet())
        }
    }
}
