package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RetainedLinearQueriesTest {
    @Test
    fun `a refuted bound leaves a sibling exact source witness available`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val plan = model.componentPlan()
        val queries = RetainedLinearQueries(model, TheoryParams(), null) { stop, _ ->
            plan.search(model, emptyMap(), cancellation = stop)
        }

        queries.use {
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(
                ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ZERO)), Cancellation.Never))
            val result = it.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never)

            assertEquals("1/3", assertIs<ExactNeighborhoodResult.Witness>(result).sample.exactReals?.single().toString())
        }
    }

    @Test
    fun `representation decline and cancellation leave no sibling bounds`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val plan = model.componentPlan()
        val queries = RetainedLinearQueries(model, TheoryParams(), null) { stop, _ ->
            plan.search(model, emptyMap(), cancellation = stop)
        }
        val huge = BigFraction.of(parseBigInt("1" + "0".repeat(1300)), BIG_ONE)

        queries.use {
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(
                ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to huge)), Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(
                ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE), emptyMap()), Cancellation { true }))
            val zero = mapOf(0 to BigFraction.ZERO)
            val result = it.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), Cancellation.Never)

            assertEquals(BigFraction.ZERO, assertIs<ExactNeighborhoodResult.Witness>(result).sample.exactReals?.single())
        }
    }

    @Test
    fun `empty source queries cannot renew their native check allowance`() {
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val plan = model.componentPlan()
        val queries = RetainedLinearQueries(model, TheoryParams(maxLeaves = 1), null) { stop, _ ->
            plan.search(model, emptyMap(), maxChecks = 1, cancellation = stop)
        }
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        queries.use {
            assertIs<ExactNeighborhoodResult.Witness>(it.check(condition, Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(condition, Cancellation.Never))

            assertEquals(1L, it.work)
        }
    }
}
