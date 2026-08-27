package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.ir.IntDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearLinearizerTest {

    private class RecordingBuilder : RelaxationBuilder {
        data class Row(
            val op: LinearOp,
            val vars: List<Int>,
            val coeffs: List<Long>,
            val bound: Long,
            val contribution: Contribution,
        )

        val rows = mutableListOf<Row>()

        override fun linearRow(
            op: LinearOp,
            intVars: IntArray,
            coeffs: LongArray,
            bound: Long,
            contribution: Contribution,
        ) {
            rows += Row(op, intVars.toList(), coeffs.toList(), bound, contribution)
        }

        // Unused by the Linear path under test.
        override fun boolRow(
            literals: IntArray,
            weights: LongArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) = error("unused")

        override fun hullEnabled(): Boolean = true
        override fun intColumn(intVar: Int): Int = error("unused")
        override fun boolColumn(boolVar: Int): Int = error("unused")
        override fun auxColumn(lo: Long, hi: Long, presence: LongArray?): Int = error("unused")
        override fun liveDomain(intVar: Int): IntDomain = error("unused")
        override fun declaredDomain(intVar: Int): IntDomain = error("unused")
        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) =
            error("unused")

        override fun bigMRow(
            columns: IntArray,
            coeffs: LongArray,
            op: LinearOp,
            rhs: Long,
            global: Boolean,
            maxSide: Boolean,
        ) = error("unused")
    }

    @Test
    fun `a linear constraint linearizes to a single core row over its terms`() {
        val linear = Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 5)
        val builder = RecordingBuilder()

        linear.linearize(builder, factorId = 0)

        assertEquals(1, builder.rows.size)
        assertEquals(LinearOp.LE, builder.rows[0].op)
        assertEquals(5L, builder.rows[0].bound)
        assertEquals(Contribution.CORE, builder.rows[0].contribution)
        assertEquals(linear.vars.toList(), builder.rows[0].vars)
        assertEquals(checkNotNull(linear.integerConstants).coeffs.toList(), builder.rows[0].coeffs)
    }

    @Test
    fun `a factor with no linear relaxation contributes nothing`() {
        val allDifferent = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)
        val builder = RecordingBuilder()

        allDifferent.linearize(builder, factorId = 0)
        assertTrue(builder.rows.isEmpty())
    }
}
