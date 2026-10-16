package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class AllDifferentTest {
    @Test
    fun `wide finite distinctness should retain enforcement beside real completion`() {
        val source = Problem(0, 3,
            arrayOf(IntDomain(0, 3000000000), IntDomain(0, 3000000000), IntDomain(2, 2)),
            arrayOf(AllDifferent(intArrayOf(0, 1, 2)),
                Linear(intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), intArrayOf(0), doubleArrayOf(-1.0),
                    LinearOp.EQ, 0.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())
        queries.use {
            val duplicate = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO, 2 to BigFraction.ofLong(2))
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(
                ExactNeighborhoodCondition(emptyMap(), duplicate, duplicate), Cancellation.Never))
            val distinct = mapOf(0 to BigFraction.ONE, 1 to BigFraction.ZERO, 2 to BigFraction.ofLong(2))

            val sample = assertIs<ExactNeighborhoodResult.Witness>(it.check(
                ExactNeighborhoodCondition(emptyMap(), distinct, distinct), Cancellation.Never)).sample

            assertEquals(1.0, sample.realValue(0))
        }
    }

    @Test
    fun `window hints should not change distinctness identity`() {
        val vars = intArrayOf(0, 1, 2)

        assertEquals(AllDifferent(vars).structuralKey(),
            AllDifferent(vars, domainMin = 5, domainSize = 7).structuralKey())
    }

    @Test
    fun `presence roles should remain paired with their integer positions`() {
        val first = Lit.make(0, true)
        val second = Lit.make(1, false)
        val relation = AllDifferent(intArrayOf(0, 1, 2), intArrayOf(first, second, second))
        val other = AllDifferent(intArrayOf(0, 1, 2), intArrayOf(second, first, second))

        assertNotEquals(relation.structuralKey(), other.structuralKey())
    }

    @Test
    fun `remapping should retain paired presence roles and excluded values`() {
        val first = Lit.make(0, true)
        val second = Lit.make(1, false)
        val relation = AllDifferent(intArrayOf(0, 1, 2), intArrayOf(first, second, second), longArrayOf(0))
        val mapping = VarRemap(intArrayOf(1, 0), intArrayOf(2, 1, 0))
        val expected = AllDifferent(intArrayOf(2, 1, 0),
            intArrayOf(Lit.make(1, true), Lit.make(0, false), Lit.make(0, false)), longArrayOf(0))

        assertEquals(expected.structuralKey(), relation.remap(mapping).structuralKey())
        assertEquals(expected.structuralKey().hashCode(), relation.remapStructuralHash(mapping))
    }
}
