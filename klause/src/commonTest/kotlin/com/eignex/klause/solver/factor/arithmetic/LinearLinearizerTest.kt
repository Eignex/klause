package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Contribution
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.NoLinearizer
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.global.AllDifferent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearLinearizerTest {

    private class RecordingBuilder : RelaxationBuilder {
        data class Row(
            val op: LinearOp,
            val vars: List<Int>,
            val coeffs: List<Int>,
            val bound: Long,
            val contribution: Contribution,
        )

        val rows = mutableListOf<Row>()

        override fun linearRow(
            op: LinearOp,
            intVars: IntArray,
            coeffs: IntArray,
            bound: Long,
            contribution: Contribution,
        ) {
            rows += Row(op, intVars.toList(), coeffs.toList(), bound, contribution)
        }

        // Unused by the Linear path under test.
        override fun boolRow(literals: IntArray, weights: IntArray?, op: LinearOp, bound: Long, contribution: Contribution) =
            error("unused")

        override fun hullEnabled(): Boolean = true
        override fun intColumn(intVar: Int): Int = error("unused")
        override fun boolColumn(boolVar: Int): Int = error("unused")
        override fun auxColumn(lo: Long, hi: Long, presence: IntArray?): Int = error("unused")
        override fun liveDomain(intVar: Int): IntDomain = error("unused")
        override fun declaredDomain(intVar: Int): IntDomain = error("unused")
        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) =
            error("unused")
    }

    @Test
    fun `a linear constraint linearizes to a single core row over its terms`() {
        val linear = Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 5)
        val builder = RecordingBuilder()

        linear.asLinearizer().linearize(builder, factorId = 0)

        assertEquals(1, builder.rows.size)
        assertEquals(LinearOp.LE, builder.rows[0].op)
        assertEquals(5L, builder.rows[0].bound)
        assertEquals(Contribution.CORE, builder.rows[0].contribution)
        assertEquals(linear.vars.toList(), builder.rows[0].vars)
        assertEquals(linear.coeffs.toList(), builder.rows[0].coeffs)
    }

    @Test
    fun `a factor with no linear relaxation contributes nothing`() {
        val allDifferent = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)
        val builder = RecordingBuilder()

        assertSame(NoLinearizer, allDifferent.asLinearizer())
        allDifferent.asLinearizer().linearize(builder, factorId = 0)
        assertTrue(builder.rows.isEmpty())
    }
}
