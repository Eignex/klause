package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExactIntegerRowPropagationTest {
    @Test
    fun `a wider equality span explains comparison guard polarities`() {
        for ((relation, expected) in listOf(LinearOp.EQ to true, LinearOp.NE to false,
            LinearOp.LE to true, LinearOp.GE to true)) {
            val model = Problem(numBoolVars = 3,
                intBounds = IntBounds.fromModelBounds(LongArray(4) { -100 }, LongArray(4) { 100 }, null, null),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
                    ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.EQ, 5),
                    ReifiedLinear(2, intArrayOf(1, -1, -1), intArrayOf(0, 2, 3), relation, -2),
                ))
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
            session.initialize()
            session.push(SearchDecision.Bool(0))
            session.push(SearchDecision.Bool(2))
            val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))

            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

            assertEquals(expected, session.boolValue(2))
            assertEquals(setOf(1, 3, Lit.make(2, expected)), session.reasonFor(2)?.literals?.toSet())
        }
    }

    @Test
    fun `a false wider disequality contributes its asserted negative polarity`() {
        val model = Problem(numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(3) { -100 }, LongArray(3) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.NE, 3),
                ReifiedLinear(1, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
            ))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        session.initialize()
        session.push(SearchDecision.Bool(1))
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(true, session.boolValue(1))
        assertEquals(setOf(0, 2), session.reasonFor(1)?.literals?.toSet())
    }

    @Test
    fun `an inconsistent active wider equality produces its source clause`() {
        val model = Problem(numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(3) { -100 }, LongArray(3) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 4),
            ))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))

        val conflict = assertIs<ComponentResult.Conflict>(propagation.propagate(session, Cancellation.Never))

        assertEquals(setOf(1, 3), conflict.explanation?.literals?.toSet())
    }

    @Test
    fun `unconditional wider equalities supply a root implication`() {
        val model = Problem(numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(4) { -100 }, LongArray(4) { 100 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.EQ, 5),
                ReifiedLinear(0, intArrayOf(1, -1, -1), intArrayOf(0, 2, 3), LinearOp.EQ, -2),
            ))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        session.initialize()
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(true, session.boolValue(0))
        assertEquals(listOf(0), session.reasonFor(0)?.literals?.toList())
    }

    @Test
    fun `a unit only derivation is outside the wider equality mechanism`() {
        val model = Problem(numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(5) { -100 }, LongArray(5) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
                Linear(intArrayOf(1, 1, -1), intArrayOf(2, 3, 4), LinearOp.EQ, 0),
            ))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never)

        assertNull(session.boolValue(2))
    }

    @Test
    fun `an original wider row reduced to one coordinate is insufficient`() {
        val model = Problem(numBoolVars = 4,
            intBounds = IntBounds.fromModelBounds(LongArray(3) { -100 }, LongArray(3) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
                ReifiedLinear(2, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                ReifiedLinear(3, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 3),
            ))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(4))
        session.initialize()
        repeat(3) { session.push(SearchDecision.Bool(Lit.make(it, true))) }
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))

        propagation.propagate(session, Cancellation.Never)

        assertNull(session.boolValue(3))
    }

    @Test
    fun `premises are checked again before an implication is published`() {
        val model = Problem(numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(3) { -100 }, LongArray(3) { 100 }, null, null),
            factors = Array(2) { guard ->
                ReifiedLinear(guard, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3)
            })
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never) { stage ->
            if (stage == "SPARSE_EQUALITY_MATCH") session.popTo(0)
        })

        propagation.propagate(session, Cancellation.Never)

        assertNull(session.boolValue(1))
    }

    @Test
    fun `retraction removes an equality from the next proof snapshot`() {
        val model = Problem(numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(4) { -100 }, LongArray(4) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.EQ, 5),
                ReifiedLinear(2, intArrayOf(1, -1, -1), intArrayOf(0, 2, 3), LinearOp.EQ, -2),
            ))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))
        propagation.propagate(session, Cancellation.Never)
        session.popTo(0)
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(3))

        propagation.propagate(session, Cancellation.Never)

        assertNull(session.boolValue(2))
    }

    @Test
    fun `another context cannot reuse the first context equality facts`() {
        val model = Problem(numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(3) { -100 }, LongArray(3) { 100 }, null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
                ReifiedLinear(1, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
            ))
        val first = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        first.initialize()
        first.push(SearchDecision.Bool(0))
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))
        propagation.propagate(first, Cancellation.Never)
        val second = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        second.initialize()

        propagation.propagate(second, Cancellation.Never)

        assertNull(second.boolValue(1))
    }

    @Test
    fun `cancellation after a publication withholds later targets`() {
        val model = Problem(numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(3) { -100 }, LongArray(3) { 100 }, null, null),
            factors = Array(3) { guard ->
                ReifiedLinear(guard, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3)
            })
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))
        var stopped = false

        val result = propagation.propagate(session, Cancellation { stopped }) {
            stopped = true
            ComponentResult.Consistent
        }

        assertIs<ComponentResult.Indeterminate>(result)
        assertEquals(true, session.boolValue(1))
        assertNull(session.boolValue(2))
    }

    @Test
    fun `an indeterminate receiver leaves the callback indeterminate`() {
        val model = Problem(numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(3) { -100 }, LongArray(3) { 100 }, null, null),
            factors = Array(2) { guard ->
                ReifiedLinear(guard, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3)
            })
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
        session.initialize()
        session.push(SearchDecision.Bool(0))
        val propagation = assertNotNull(ExactIntegerRowPropagation.prepare(model, Cancellation.Never))

        val result = propagation.propagate(session, Cancellation.Never) { ComponentResult.Indeterminate }

        assertIs<ComponentResult.Indeterminate>(result)
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `a disjunction cannot supply independent equality facts`() {
        val source = ReifiedLinear(0, intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3)
        val disjunction = object : Factor by source {
            override val linearForm = LinearForm.Disjunction(source.linearForm.rows)
        }
        val model = Problem(numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(3) { -100 }, LongArray(3) { 100 }, null, null),
            factors = arrayOf(disjunction))

        val propagation = ExactIntegerRowPropagation.prepare(model, Cancellation.Never)

        assertNull(propagation)
    }

    @Test
    fun `default complete arithmetic receives an implied source guard once`() {
        val model = Problem(numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(4) { -100 }, LongArray(4) { 100 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.EQ, 5),
                ReifiedLinear(0, intArrayOf(1, -1, -1), intArrayOf(0, 2, 3), LinearOp.EQ, -2),
            ))
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(3))

            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.propagate())

            assertEquals(true, session.boolValue(0))
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("SPARSE_EQUALITY_PUBLISHED"))
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("SPARSE_EQUALITY_RECEIVED"))
        }
    }
}
