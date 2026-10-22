package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearSpecializationTest {
    @Test
    fun `normalizing greater-equal rows at signed word endpoints should preserve pinned solutions`() {
        for ((coefficient, bound) in listOf(2L to Long.MIN_VALUE, Long.MIN_VALUE to 0L,
            Long.MIN_VALUE to Long.MIN_VALUE, Long.MIN_VALUE + 2 to 0L, 2L to Long.MIN_VALUE + 2, 2L to 1L)) {
            val source = Linear(longArrayOf(coefficient), intArrayOf(0), LinearOp.GE, bound)
            val normalized = assertNotNull((object : Factor by source {}).equivalentLinear())
            for (value in -1L..2L) {
                val expected = bigIntOf(coefficient) * bigIntOf(value) >= bigIntOf(bound)
                for (row in listOf(source, normalized)) {
                    val state = PropagationState(
                        Problem(0, 1, arrayOf(IntDomain(value, value)), arrayOf(row)),
                        Assumptions.None,
                    )

                    val consistent = state.factorAt(0).propagate(state, 0)

                    assertEquals(expected, consistent, "$coefficient * $value >= $bound")
                }
            }
        }
    }

    @Test
    fun `a Boolean row exceeding the kernel activity range should be left unspecialized`() {
        val literals = intArrayOf(Lit.make(0, true), Lit.make(1, true))
        val factor = object : Factor by Clause(literals) {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(LinearRow.ofBools(literals, longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), LinearOp.LE, 1)),
            )
        }

        assertNull(factor.equivalentPseudoBoolean())
    }

    @Test
    fun `unsafe Boolean residuals should decline through direct and delegated source views`() {
        val sources = listOf(
            PseudoBoolean(longArrayOf(Long.MIN_VALUE), intArrayOf(Lit.make(0, false)), PbOp.GE, 0),
            PseudoBoolean(longArrayOf(1), intArrayOf(Lit.make(0, true)), PbOp.LE, Long.MIN_VALUE),
            PseudoBoolean(longArrayOf(2), intArrayOf(Lit.make(0, true)), PbOp.GE, Long.MIN_VALUE),
            PseudoBoolean(longArrayOf(2), intArrayOf(Lit.make(0, true)), PbOp.GE, Long.MIN_VALUE + 2),
        )
        for (source in sources) {
            for (factor in listOf(source, object : Factor by source {})) {
                assertNull(factor.equivalentPseudoBoolean())
            }
        }
    }

    @Test
    fun `an exact declared disequality should retain its comparison`() {
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
    fun `partial and multi-row declarations should not authorize single-row replacement`() {
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
    fun `an undecided activator should not authorize unconditional replacement`() {
        val factor = object : Factor by ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3) {}

        assertNull(factor.equivalentLinear())
    }
}
