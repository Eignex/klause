package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Contribution
import com.eignex.klause.solver.NoLinearizer
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.bool.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearLinearizerTest {

    private class RecordingBuilder : RelaxationBuilder {
        data class Row(val op: LinearOp, val vars: List<Int>, val coeffs: List<Int>, val bound: Long)

        val rows = mutableListOf<Row>()

        override fun linearRow(op: LinearOp, intVars: IntArray, coeffs: IntArray, bound: Long) {
            rows += Row(op, intVars.toList(), coeffs.toList(), bound)
        }
    }

    @Test
    fun `a linear constraint linearizes to a single core row over its terms`() {
        val linear = Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 5)
        val builder = RecordingBuilder()
        val linearizer = linear.asLinearizer()

        linearizer.linearize(builder, factorId = 0)

        assertEquals(Contribution.CORE, linearizer.contribution)
        assertEquals(1, builder.rows.size)
        assertEquals(LinearOp.LE, builder.rows[0].op)
        assertEquals(5L, builder.rows[0].bound)
        assertEquals(linear.vars.toList(), builder.rows[0].vars)
        assertEquals(linear.coeffs.toList(), builder.rows[0].coeffs)
    }

    @Test
    fun `a factor with no linear relaxation contributes nothing`() {
        val clause = Clause(intArrayOf(0, 1))
        val builder = RecordingBuilder()

        assertSame(NoLinearizer, clause.asLinearizer())
        clause.asLinearizer().linearize(builder, factorId = 0)
        assertTrue(builder.rows.isEmpty())
    }
}
