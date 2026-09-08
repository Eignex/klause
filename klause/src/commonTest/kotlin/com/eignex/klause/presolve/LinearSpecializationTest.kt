package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.linearRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearSpecializationTest {
    @Test
    fun `a Boolean row exceeding the kernel activity range is left unspecialized`() {
        val literals = intArrayOf(Lit.make(0, true), Lit.make(1, true))
        val factor = object : Factor by Clause(literals) {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(LinearRow.ofBools(literals, longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), LinearOp.LE, 1)),
            )
        }

        assertNull(factor.equivalentPseudoBoolean())
    }

    @Test
    fun `an exact declared disequality retains its comparison`() {
        val source = Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.NE, 3)
        val factor = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(LinearRow.ofInts(intArrayOf(0, 1), longArrayOf(1, 2), LinearOp.NE, 3)),
            )
        }

        val row = assertNotNull(factor.equivalentLinear())

        assertEquals(LinearOp.NE, row.op)
        assertEquals(listOf(1L, 2L), row.integerConstants?.coeffs?.toList())
        assertEquals(3L, row.integerConstants?.bound)
    }

    @Test
    fun `partial and multi-row declarations do not authorize single-row replacement`() {
        val source = Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3)
        for (form in listOf(
            LinearForm.Relaxation(source.linearRows),
            LinearForm.Disjunction(source.linearRows),
            LinearForm.Conjunction(source.linearRows + source.linearRows),
        )) {
            val factor = object : Factor by source {
                override val linearForm: LinearForm = form
            }

            assertNull(factor.equivalentLinear())
            assertNull(factor.equivalentPseudoBoolean())
        }
    }

    @Test
    fun `an undecided activator does not authorize unconditional replacement`() {
        val factor = object : Factor by ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3) {}

        assertNull(factor.equivalentLinear())
    }
}
