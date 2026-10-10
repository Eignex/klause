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
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ExactLiraDifferenceTest {
    @Test
    fun `difference cycles in a larger integer theory cite their guard and retract`() {
        val open = Bits(3).also { for (variable in 0..2) it.set(variable) }
        val source = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.GE, 1),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.GE, -1),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, -100),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertContentEquals(intArrayOf(Lit.make(0, false)), assertNotNull(conflict.explanation).literals)
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
        }
    }

    @Test
    fun `a complemented scaled comparison uses the exact integer threshold`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 0),
            ),
        )
        val component = ExactLiraDifference(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        })
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(0, false)))

        val conflict = assertIs<ComponentResult.Conflict>(component.propagate(session, Cancellation.Never))

        assertContentEquals(intArrayOf(Lit.make(0, true)), assertNotNull(conflict.explanation).literals)
    }

    @Test
    fun `cancelled difference checks withhold a source conflict`() {
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), Bits(1), Bits(1)),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)),
        )
        val component = ExactLiraDifference(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        })

        assertIs<ComponentResult.Indeterminate>(component.propagate(SearchSession(emptyList()), Cancellation { true }))
    }
}
