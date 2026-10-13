package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class RetainedLinearQueriesTest {
    @Test
    fun `failed root initialization preserves its failure and releases every peer`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val primary = IllegalArgumentException("initialization")
        val cleanup = IllegalStateException("cleanup")
        val closed = ArrayList<Int>()
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("peers",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            List(2) { index -> object : SearchComponent, AutoCloseable {
                override fun initialize(context: SearchContext): ComponentResult = throw primary
                override fun close() {
                    closed += index
                    if (index == 1) throw cleanup
                }
            } }
        }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))
        val queries = RetainedLinearQueries(source, TheoryParams(), null) { stop, _ ->
            prepared.open(Long.MAX_VALUE, stop, SearchLearnedDbParams(), null, null)
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            queries.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never)
        }
        queries.close()

        assertSame(primary, failure)
        assertSame(cleanup, failure.suppressedExceptions.single())
        assertEquals(listOf(1, 0), closed)
    }

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

            val sample = assertIs<ExactNeighborhoodResult.Witness>(result).sample
            assertEquals("1/3", sample.exactReals?.single().toString())
        }
    }

    @Test
    fun `an incomplete query leaves no sibling bounds`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val plan = model.componentPlan()
        val queries = RetainedLinearQueries(model, TheoryParams(), null) { stop, _ ->
            plan.search(model, emptyMap(), cancellation = stop)
        }
        val huge = BigFraction.of(parseBigInt("1" + "0".repeat(1300)), BIG_ONE)

        queries.use {
            for ((condition, stop) in listOf(
                ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to huge)) to Cancellation.Never,
                ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE), emptyMap()) to
                    Cancellation { true },
            )) {
                assertIs<ExactNeighborhoodResult.Undecided>(it.check(condition, stop))
                val zero = mapOf(0 to BigFraction.ZERO)
                val result = it.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), Cancellation.Never)

                val sample = assertIs<ExactNeighborhoodResult.Witness>(result).sample
                assertEquals(BigFraction.ZERO, sample.exactReals?.single())
            }
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
