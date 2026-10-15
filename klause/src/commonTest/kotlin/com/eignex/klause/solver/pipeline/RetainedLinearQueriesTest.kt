package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.PreparingSearchComponent
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetainedLinearQueriesTest {
    @Test
    fun `a paused root resumes before applying a sibling condition`() {
        val source = Problem(1, 0, emptyArray(), emptyArray())
        var paused = false
        var openings = 0
        var initializations = 0
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("root",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            openings++
            listOf(object : PreparingSearchComponent {
                override fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?> = sequence {
                    initializations++
                    assertTrue(context.consumeCheck())
                    paused = true
                    yield(null)
                    yield(context.publish(Lit.make(0, true)))
                }.iterator()
            })
        }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))
        val queries = RetainedLinearQueries(source, TheoryParams(), null) { stop, _ ->
            prepared.open(Long.MAX_VALUE, stop, SearchLearnedDbParams(), null, null)
        }

        queries.use {
            val contrary = ExactNeighborhoodCondition(mapOf(0 to false), emptyMap(), emptyMap())
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(contrary, Cancellation { paused }))
            assertNull(it.rootBooleanValue(0))
            val spent = it.work
            assertTrue(spent > 0)
            assertTrue(it.prepareBooleanCenter(Cancellation.Never))

            assertEquals(1, openings)
            assertEquals(1, initializations)
            assertEquals(spent, it.work)
            assertEquals(true, it.rootBooleanValue(0))
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(contrary, Cancellation.Never))
            assertEquals(true, it.rootBooleanValue(0))
        }
    }

    @Test
    fun `closing a paused root releases its suspended component once`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        var paused = false
        var closed = 0
        var completed = false
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("root",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            listOf(object : PreparingSearchComponent, AutoCloseable {
                override fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?> = sequence {
                    paused = true
                    yield(null)
                    completed = true
                    yield(ComponentResult.Consistent)
                }.iterator()
                override fun close() { closed++ }
            })
        }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))
        val queries = RetainedLinearQueries(source, TheoryParams(), null) { stop, _ ->
            prepared.open(Long.MAX_VALUE, stop, SearchLearnedDbParams(), null, null)
        }
        assertFalse(queries.prepareBooleanCenter(Cancellation { paused }))

        queries.close()
        queries.close()

        assertEquals(1, closed)
        assertFalse(completed)
    }

    @Test
    fun `an indeterminate initializer is discarded before a sibling root`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        var openings = 0
        var closed = 0
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("root",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            val decline = openings++ == 0
            listOf(object : SearchComponent, AutoCloseable {
                override fun initialize(context: SearchContext): ComponentResult =
                    if (decline) ComponentResult.Indeterminate else ComponentResult.Consistent
                override fun close() { closed++ }
            })
        }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))
        val queries = RetainedLinearQueries(source, TheoryParams(), null) { stop, _ ->
            prepared.open(Long.MAX_VALUE, stop, SearchLearnedDbParams(), null, null)
        }

        queries.use {
            assertFalse(it.prepareBooleanCenter(Cancellation.Never))
            assertEquals(1, closed)
            assertTrue(it.prepareBooleanCenter(Cancellation.Never))
            assertEquals(2, openings)
        }
        assertEquals(2, closed)
    }

    @Test
    fun `cancellation at completed initialization does not replay the root`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        var paused = false
        var initializations = 0
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("root",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            listOf(object : SearchComponent {
                override fun initialize(context: SearchContext): ComponentResult {
                    initializations++
                    paused = true
                    return ComponentResult.Consistent
                }
            })
        }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))
        val queries = RetainedLinearQueries(source, TheoryParams(), null) { stop, _ ->
            prepared.open(Long.MAX_VALUE, stop, SearchLearnedDbParams(), null, null)
        }

        queries.use {
            assertFalse(it.prepareBooleanCenter(Cancellation { paused }))
            assertTrue(it.prepareBooleanCenter(Cancellation.Never))

            assertEquals(1, initializations)
        }
    }

    @Test
    fun `generated center preparation preserves cancellation and one root opening`() {
        val source = Problem(1, 0, emptyArray(), emptyArray())
        var openings = 0
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("root",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            openings++
            listOf(object : SearchComponent {
                override fun initialize(context: SearchContext): ComponentResult = context.publish(Lit.make(0, true))
            })
        }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))
        val queries = RetainedLinearQueries(source, TheoryParams(), null) { stop, _ ->
            prepared.open(Long.MAX_VALUE, stop, SearchLearnedDbParams(), null, null)
        }

        queries.use {
            assertFalse(it.prepareBooleanCenter(Cancellation { true }))
            assertEquals(0, openings)
            assertTrue(it.prepareBooleanCenter(Cancellation.Never))
            assertTrue(it.prepareBooleanCenter(Cancellation.Never))

            assertEquals(1, openings)
            assertEquals(true, it.rootBooleanValue(0))
            assertEquals(0L, it.work)
        }
    }

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
