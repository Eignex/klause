package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetainedLinearQueriesTest {
    @Test
    fun `unpinned retained query should select its prepared component preference`() {
        val model = Problem(
            1, 0, emptyArray(), arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(1.0, 2.0), LinearOp.LE, 0.0),
            ),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(0.0, 0.0),
        )
        val plan = model.componentPlan()
        val queries = RetainedLinearQueries(model, TheoryParams(), null) { stop, state ->
            plan.search(model, emptyMap(), Long.MAX_VALUE, stop, SearchLearnedDbParams(), state.smt)
        }

        queries.use {
            val result = it.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never)

            assertEquals(true, assertIs<ExactNeighborhoodResult.Witness>(result).sample.boolValue(0))
            assertEquals(1L, it.stats.openHints.usage.componentSelections)
        }
    }

    @Test
    fun `retained query caller preference should precede its component preference`() {
        val model = Problem(1, 0, emptyArray(), emptyArray())
        var componentCalls = 0
        val queries = RetainedLinearQueries(model, TheoryParams(), null,
            hints = { _, _ -> SearchCandidateHints { false } }) { _, _ ->
            PlannedSearch(SearchSession(emptyList()), null, null, SearchInvocationResources(),
                SearchCandidateHints { componentCalls++; true })
        }

        queries.use {
            val result = it.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never)

            assertEquals(false, assertIs<ExactNeighborhoodResult.Witness>(result).sample.boolValue(0))
            assertEquals(0, componentCalls)
            assertEquals(1L, it.stats.openHints.usage.callerSelections)
        }
    }

    @Test
    fun `retained query should search the complement of a refuted component preference`() {
        val model = Problem(1, 0, emptyArray(), emptyArray())
        val checked = ArrayList<Boolean?>()
        val component = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                val value = context.boolValue(0)
                checked += value
                return if (value == true) ComponentCheck.Infeasible() else ComponentCheck.Feasible
            }
        }
        val queries = RetainedLinearQueries(model, TheoryParams(), null) { _, _ ->
            PlannedSearch(SearchSession(listOf(component)), null, null, SearchInvocationResources(),
                SearchCandidateHints { true })
        }

        queries.use {
            val result = it.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never)

            assertEquals(false, assertIs<ExactNeighborhoodResult.Witness>(result).sample.boolValue(0))
            assertEquals(listOf(true, false), checked)
        }
    }

    @Test
    fun `fully pinned retained query should not consult component preferences`() {
        val model = Problem(1, 0, emptyArray(), emptyArray())
        val queries = RetainedLinearQueries(model, TheoryParams(), null) { _, _ ->
            PlannedSearch(SearchSession(emptyList()), null, null, SearchInvocationResources(),
                SearchCandidateHints { error("a pinned Boolean has no split") })
        }

        queries.use {
            val result = it.check(ExactNeighborhoodCondition(mapOf(0 to false), emptyMap(), emptyMap()), Cancellation.Never)

            assertEquals(false, assertIs<ExactNeighborhoodResult.Witness>(result).sample.boolValue(0))
            assertEquals(0L, it.stats.openHints.usage.componentConsultations)
        }
    }

    @Test
    fun `retained component preferences should not outlive a query point or owner`() {
        val model = Problem(1, 0, emptyArray(), emptyArray())
        lateinit var search: PlannedSearch
        var live = false
        val component = object : SearchComponent, AutoCloseable {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                live = true
                return ComponentResult.Consistent
            }
            override fun retract(decisionLevel: Int) { live = false }
            override fun close() { live = false }
        }
        val resources = SearchInvocationResources().apply { ownComponent(component) }
        val queries = RetainedLinearQueries(model, TheoryParams(), null) { stop, _ ->
            PlannedSearch(SearchSession(listOf(component)), null, null, resources,
                SearchCandidateHints { true.takeIf { live && !stop() } }).also { search = it }
        }
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        assertIs<ExactNeighborhoodResult.Witness>(queries.check(condition, Cancellation.Never))

        assertNull(search.candidateHints.preferredBool(0))
        assertIs<ExactNeighborhoodResult.Undecided>(queries.check(condition, Cancellation { true }))
        assertIs<ExactNeighborhoodResult.Witness>(queries.check(condition, Cancellation.Never))
        queries.close()

        assertNull(search.candidateHints.preferredBool(0))
        assertEquals(2L, queries.stats.openHints.usage.componentSelections)
    }

    @Test
    fun `retained queries should not renew their containing node allowance`() {
        val model = Problem(1, 0, emptyArray(), emptyArray())
        val plan = model.componentPlan()
        val budget = NodeBudget(1)
        val queries = RetainedLinearQueries(model, TheoryParams(nodeBudget = budget), null) { stop, _ ->
            plan.search(model, emptyMap(), cancellation = stop)
        }
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        queries.use {
            assertIs<ExactNeighborhoodResult.Witness>(it.check(condition, Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(condition, Cancellation.Never))

            assertEquals(1L, budget.spent)
        }
    }

    @Test
    fun `generated center preparation should preserve cancellation and one root opening`() {
        val source = Problem(1, 0, emptyArray(), emptyArray())
        var openings = 0
        val registration = PreparedSearchRegistration(
            source,
            ExecutionComponentDescriptor(
                "root",
                ComponentResponsibility.BOOLEAN,
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        ) {
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
    fun `failed root initialization should preserve its failure and release every peer`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val primary = IllegalArgumentException("initialization")
        val cleanup = IllegalStateException("cleanup")
        val closed = ArrayList<Int>()
        val registration = PreparedSearchRegistration(
            source,
            ExecutionComponentDescriptor(
                "peers",
                ComponentResponsibility.BOOLEAN,
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        ) {
            List(2) { index ->
                object : SearchComponent, AutoCloseable {
                    override fun initialize(context: SearchContext): ComponentResult = throw primary
                    override fun close() {
                        closed += index
                        if (index == 1) throw cleanup
                    }
                }
            }
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
    fun `refuted bound should leave a sibling exact source witness available`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(3.0),
                    LinearOp.EQ,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val plan = model.componentPlan()
        val queries = RetainedLinearQueries(model, TheoryParams(), null) { stop, _ ->
            plan.search(model, emptyMap(), cancellation = stop)
        }

        queries.use {
            assertIs<ExactNeighborhoodResult.Refuted>(
                it.check(
                    ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ZERO)),
                    Cancellation.Never,
                ),
            )
            val result = it.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never)

            val sample = assertIs<ExactNeighborhoodResult.Witness>(result).sample
            assertEquals("1/3", sample.exactReals?.single().toString())
        }
    }

    @Test
    fun `incomplete query should leave no sibling bounds`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
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
    fun `empty source queries should not renew their native check allowance`() {
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
