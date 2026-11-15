package com.eignex.klause.propagation

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PropagationControlTest {
    @Test
    fun `a propagation scope should charge its containing owner`() {
        var containing = 0L
        var additional = 0L
        val parent = ExecutionControl(Cancellation.Never, WorkMeter { containing += it })
        val extra = ExecutionControl(Cancellation.Never, WorkMeter { additional += it })

        PropagationControl(parent, extra).charge(7L)

        assertEquals(7L, containing)
        assertEquals(0L, additional)
    }

    @Test
    fun `a nested propagation allowance should retain the tighter quota`() {
        val budget = PresolveBudget(100L)
        val control = PropagationControl(budget.slice(80L), budget.slice(10L))
        val arithmetic = PolynomialArithmetic(control)

        assertFailsWith<PolynomialInterrupted> {
            arithmetic.execution.operation { repeat(20) { arithmetic.step() } }
        }

        assertEquals(10L, budget.spent())
    }
}
